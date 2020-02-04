package com.geeksville.mesh

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.geeksville.android.DebugLogFile
import com.geeksville.android.Logging
import com.geeksville.concurrent.DeferredExecution
import com.geeksville.util.toRemoteExceptions
import com.google.protobuf.util.JsonFormat
import java.util.*

/* Info for the esp32 device side code.  See that source for the 'gold' standard docs on this interface.

MeshBluetoothService UUID 6ba1b218-15a8-461f-9fa8-5dcae273eafd

FIXME - notify vs indication for fromradio output.  Using notify for now, not sure if that is best
FIXME - in the esp32 mesh management code, occasionally mirror the current net db to flash, so that if we reboot we still have a good guess of users who are out there.
FIXME - make sure this protocol is guaranteed robust and won't drop packets

"According to the BLE specification the notification length can be max ATT_MTU - 3. The 3 bytes subtracted is the 3-byte header(OP-code (operation, 1 byte) and the attribute handle (2 bytes)).
In BLE 4.1 the ATT_MTU is 23 bytes (20 bytes for payload), but in BLE 4.2 the ATT_MTU can be negotiated up to 247 bytes."

MAXPACKET is 256? look into what the lora lib uses. FIXME

Characteristics:
UUID
properties
description

8ba2bcc2-ee02-4a55-a531-c525c5e454d5
read
fromradio - contains a newly received packet destined towards the phone (up to MAXPACKET bytes? per packet).
After reading the esp32 will put the next packet in this mailbox.  If the FIFO is empty it will put an empty packet in this
mailbox.

f75c76d2-129e-4dad-a1dd-7866124401e7
write
toradio - write ToRadio protobufs to this charstic to send them (up to MAXPACKET len)

ed9da18c-a800-4f66-a670-aa7547e34453
read|notify|write
fromnum - the current packet # in the message waiting inside fromradio, if the phone sees this notify it should read messages
until it catches up with this number.
  The phone can write to this register to go backwards up to FIXME packets, to handle the rare case of a fromradio packet was dropped after the esp32
callback was called, but before it arrives at the phone.  If the phone writes to this register the esp32 will discard older packets and put the next packet >= fromnum in fromradio.
When the esp32 advances fromnum, it will delay doing the notify by 100ms, in the hopes that the notify will never actally need to be sent if the phone is already pulling from fromradio.
  Note: that if the phone ever sees this number decrease, it means the esp32 has rebooted.

meshMyNodeCharacteristic("ea9f3f82-8dc4-4733-9452-1f6da28892a2", BLECharacteristic::PROPERTY_READ)
mynode - read/write this to access a MyNodeInfo protobuf

meshNodeInfoCharacteristic("d31e02e0-c8ab-4d3f-9cc9-0b8466bdabe8", BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ),
nodeinfo - read this to get a series of node infos (ending with a null empty record), write to this to restart the read statemachine that returns all the node infos

meshRadioCharacteristic("b56786c8-839a-44a1-b98e-a1724c4a0262", BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ),
radio - read/write this to access a RadioConfig protobuf

meshOwnerCharacteristic("6ff1d8b6-e2de-41e3-8c0b-8fa384f64eb6", BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ)
owner - read/write this to access a User protobuf

Re: queue management
Not all messages are kept in the fromradio queue (filtered based on SubPacket):
* only the most recent Position and User messages for a particular node are kept
* all Data SubPackets are kept
* No WantNodeNum / DenyNodeNum messages are kept
A variable keepAllPackets, if set to true will suppress this behavior and instead keep everything for forwarding to the phone (for debugging)

 */

/**
 * Handles the bluetooth link with a mesh radio device.  Does not cache any device state,
 * just does bluetooth comms etc...
 *
 * This service is not exposed outside of this process.
 *
 * Note - this class intentionally dumb.  It doesn't understand protobuf framing etc...
 * It is designed to be simple so it can be stubbed out with a simulated version as needed.
 */
class RadioInterfaceService : Service(), Logging {

    companion object {
        /**
         * The RECEIVED_FROMRADIO
         * Payload will be the raw bytes which were contained within a MeshProtos.FromRadio protobuf
         */
        const val RECEIVE_FROMRADIO_ACTION = "$prefix.RECEIVE_FROMRADIO"
        const val CONNECTCHANGED_ACTION = "$prefix.CONNECT_CHANGED"

        private val BTM_SERVICE_UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        private val BTM_FROMRADIO_CHARACTER =
            UUID.fromString("8ba2bcc2-ee02-4a55-a531-c525c5e454d5")
        private val BTM_TORADIO_CHARACTER =
            UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        private val BTM_FROMNUM_CHARACTER =
            UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        /// mynode - read/write this to access a MyNodeInfo protobuf
        private val BTM_MYNODE_CHARACTER =
            UUID.fromString("ea9f3f82-8dc4-4733-9452-1f6da28892a2")

        /// nodeinfo - read this to get a series of node infos (ending with a null empty record), write to this to restart the read statemachine that returns all the node infos
        private val BTM_NODEINFO_CHARACTER =
            UUID.fromString("d31e02e0-c8ab-4d3f-9cc9-0b8466bdabe8")

        /// radio - read/write this to access a RadioConfig protobuf
        private val BTM_RADIO_CHARACTER =
            UUID.fromString("b56786c8-839a-44a1-b98e-a1724c4a0262")

        /// owner - read/write this to access a User protobuf
        private val BTM_OWNER_CHARACTER =
            UUID.fromString("6ff1d8b6-e2de-41e3-8c0b-8fa384f64eb6")

        /// This is public only so that SimRadio can bootstrap our message flow
        fun broadcastReceivedFromRadio(context: Context, payload: ByteArray) {
            val intent = Intent(RECEIVE_FROMRADIO_ACTION)
            intent.putExtra(EXTRA_PAYLOAD, payload)
            context.sendBroadcast(intent)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter!!
    }

    // Both of these are created in onCreate()
    private lateinit var device: BluetoothDevice
    private lateinit var safe: SafeBluetooth

    val service get() = safe.gatt.services.find { it.uuid == BTM_SERVICE_UUID }!!

    private lateinit var fromRadio: BluetoothGattCharacteristic
    private lateinit var fromNum: BluetoothGattCharacteristic

    lateinit var sentPacketsLog: DebugLogFile // inited in onCreate

    // for debug logging only
    private val jsonPrinter = JsonFormat.printer()

    private var isConnected = false

    /// Work that users of our service want done, which might get deferred until after
    /// we have completed our initial connection
    private val clientOperations = DeferredExecution()

    private fun broadcastConnectionChanged(isConnected: Boolean) {
        debug("Broadcasting connection=$isConnected")
        val intent = Intent(CONNECTCHANGED_ACTION)
        intent.putExtra(EXTRA_CONNECTED, isConnected)
        sendBroadcast(intent)
    }

    /// Send a packet/command out the radio link
    private fun handleSendToRadio(p: ByteArray) {

        // For debugging/logging purposes ONLY we convert back into a protobuf for readability
        val proto = MeshProtos.ToRadio.parseFrom(p)

        val json = jsonPrinter.print(proto).replace('\n', ' ')
        info("TODO sending to radio: $json")
        sentPacketsLog.log(json)
    }

    // Handle an incoming packet from the radio, broadcasts it as an android intent
    private fun handleFromRadio(p: ByteArray) {
        broadcastReceivedFromRadio(this, p)
    }

    /// Attempt to read from the fromRadio mailbox, if data is found broadcast it to android apps
    private fun doReadFromRadio() {
        if (!isConnected)
            warn("Abandoning fromradio read because we are not connected")
        else
            safe.asyncReadCharacteristic(fromRadio) {
                val b = it.getOrThrow().value

                if (b.isNotEmpty()) {
                    debug("Received ${b.size} bytes from radio")
                    handleFromRadio(b)

                    // Queue up another read, until we run out of packets
                    doReadFromRadio()
                } else {
                    debug("Done reading from radio, fromradio is empty")
                }
            }
    }


    override fun onCreate() {
        super.onCreate()

        // FIXME, let user GUI select which device we are talking to

        // Note: this call does no comms, it just creates the device object (even if the
        // device is off/not connected)
        val usetbeam = false
        val address = if (usetbeam) "B4:E6:2D:EA:32:B7" else "24:6F:28:96:C9:2A"
        info("Creating radio interface service.  device=$address")

        device = bluetoothAdapter.getRemoteDevice(address)
        // Note this constructor also does no comm
        safe = SafeBluetooth(this, device)

        // FIXME, pass in true for autoconnect - so we will autoconnect whenever the radio
        // comes in range (even if we made this connect call long ago when we got powered on)
        // see https://stackoverflow.com/questions/40156699/which-correct-flag-of-autoconnect-in-connectgatt-of-ble for
        // more info
        // FIXME, broadcast connection lost/gained via broadcastConnecionChanged
        safe.asyncConnect(true) { connRes ->
            // This callback is invoked after we are connected 

            connRes.getOrThrow() // FIXME, instead just try to reconnect?
            info("Connected to radio!")

            // FIXME - no need to discover services, instead just hardwire the characteristics (like we do for toRadio)
            safe.asyncDiscoverServices { discRes ->
                discRes.getOrThrow() // FIXME, instead just try to reconnect?
                debug("Discovered services!")

                // we begin by setting our MTU size as high as it can go
                safe.asyncRequestMtu(512) { mtuRes ->
                    debug("requested MTU result=$mtuRes")
                    mtuRes.getOrThrow()

                    fromRadio = service.getCharacteristic(BTM_FROMRADIO_CHARACTER)
                    fromNum = service.getCharacteristic(BTM_FROMNUM_CHARACTER)

                    // Now tell clients they can (finally use the api)
                    broadcastConnectionChanged(true)
                    isConnected = true

                    // Immediately broadcast any queued packets sitting on the device
                    doReadFromRadio()
                }
            }
        }

        sentPacketsLog = DebugLogFile(this, "sent_log.json")
    }

    override fun onDestroy() {
        info("Destroying radio interface service")
        sentPacketsLog.close()
        safe.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder;
    }

    /**
     * do a synchronous write operation
     */
    private fun doWrite(uuid: UUID, a: ByteArray) = toRemoteExceptions {
        if (!isConnected)
            throw RadioNotConnectedException()
        else {
            debug("queuing ${a.size} bytes to $uuid")

            // Note: we generate a new characteristic each time, because we are about to
            // change the data and we want the data stored in the closure
            val toRadio = service.getCharacteristic(uuid)
            toRadio.value = a

            safe.writeCharacteristic(toRadio)
            debug("write of ${a.size} bytes completed")
        }
    }

    /**
     * do a synchronous read operation
     */
    private fun doRead(uuid: UUID): ByteArray? = toRemoteExceptions {
        if (!isConnected)
            throw RadioNotConnectedException()
        else {
            // Note: we generate a new characteristic each time, because we are about to
            // change the data and we want the data stored in the closure
            val toRadio = service.getCharacteristic(uuid)
            var a = safe.readCharacteristic(toRadio).value
            debug("Read of $uuid got ${a.size} bytes")

            if (a.isEmpty()) // An empty bluetooth response is converted to a null response for our clients
                a = null

            a
        }
    }

    private val binder = object : IRadioInterfaceService.Stub() {
        // A write of any size to nodeinfo means restart reading
        override fun restartNodeInfo() = doWrite(BTM_NODEINFO_CHARACTER, ByteArray(0))

        override fun readMyNode() = doRead(BTM_MYNODE_CHARACTER)!!

        override fun sendToRadio(a: ByteArray) = doWrite(BTM_TORADIO_CHARACTER, a)

        override fun readRadioConfig() = doRead(BTM_RADIO_CHARACTER)!!

        override fun readOwner() = doRead(BTM_OWNER_CHARACTER)!!

        override fun writeOwner(owner: ByteArray) = doWrite(BTM_OWNER_CHARACTER, owner)

        override fun writeRadioConfig(config: ByteArray) = doWrite(BTM_RADIO_CHARACTER, config)

        override fun readNodeInfo() = doRead(BTM_NODEINFO_CHARACTER)
    }
}