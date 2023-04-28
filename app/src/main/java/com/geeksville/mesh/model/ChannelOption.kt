package com.geeksville.mesh.model

import com.geeksville.mesh.ConfigProtos.Config.LoRaConfig.ModemPreset
import com.geeksville.mesh.R

enum class ChannelOption(
    val modemPreset: ModemPreset,
    val configRes: Int,
) {
    SHORT_FAST(ModemPreset.SHORT_FAST, R.string.modem_config_short),
    SHORT_SLOW(ModemPreset.SHORT_SLOW, R.string.modem_config_slow_short),
    MEDIUM_FAST(ModemPreset.MEDIUM_FAST, R.string.modem_config_medium),
    MEDIUM_SLOW(ModemPreset.MEDIUM_SLOW, R.string.modem_config_slow_medium),
    LONG_FAST(ModemPreset.LONG_FAST, R.string.modem_config_long),
    LONG_SLOW(ModemPreset.LONG_SLOW, R.string.modem_config_slow_long),
    VERY_LONG_SLOW(ModemPreset.VERY_LONG_SLOW, R.string.modem_config_very_long),
    AIR_RATE_300(ModemPreset.AIR_RATE_300, R.string.modem_config_300),
    AIR_RATE_1200(ModemPreset.AIR_RATE_1200, R.string.modem_config_1200),
    AIR_RATE_2400(ModemPreset.AIR_RATE_2400, R.string.modem_config_2400),
    AIR_RATE_4800(ModemPreset.AIR_RATE_4800, R.string.modem_config_4800),
    AIR_RATE_9600(ModemPreset.AIR_RATE_9600, R.string.modem_config_9600),
    AIR_RATE_19200(ModemPreset.AIR_RATE_19200, R.string.modem_config_19200),
    AIR_RATE_37500(ModemPreset.AIR_RATE_37500, R.string.modem_config_37500),
    AIR_RATE_38400(ModemPreset.AIR_RATE_38400, R.string.modem_config_38400),
    AIR_RATE_62500(ModemPreset.AIR_RATE_62500, R.string.modem_config_62500);

    companion object {
        fun fromConfig(modemPreset: ModemPreset?): ChannelOption? {
            for (option in values()) {
                if (option.modemPreset == modemPreset) return option
            }
            return null
        }
    }
}