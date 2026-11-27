package com.networkanalyst.core.discovery

import com.networkanalyst.core.model.DeviceType

/**
 * Best-effort guess at what kind of device something is, from the clues it broadcasts about
 * itself. These are heuristics: a device that announces nothing stays [DeviceType.UNKNOWN].
 */
object DeviceClassifier {
    fun classify(
        isGateway: Boolean,
        isSelf: Boolean,
        services: Collection<String>,
        names: Collection<String?>,
        manufacturer: String?,
        model: String?,
        upnpDeviceType: String?,
        openPorts: Collection<Int>,
    ): DeviceType {
        if (isSelf) return DeviceType.THIS_DEVICE
        if (isGateway) return DeviceType.ROUTER
        val svc = services.map { it.lowercase() }.toSet()
        val text = (names.filterNotNull() + listOfNotNull(manufacturer, model)).joinToString(" ").lowercase()
        val upnp = upnpDeviceType?.lowercase().orEmpty()

        fun textHas(vararg words: String) = words.any { it in text }
        fun svcHas(vararg types: String) = types.any { it in svc }

        return when {
            upnp.contains("internetgatewaydevice") || upnp.contains("wlanaccesspoint") -> DeviceType.ROUTER
            svcHas("_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_scanner._tcp", "_uscan._tcp") ||
                upnp.contains(":printer:") || textHas("printer", "laserjet", "officejet", "deskjet") -> DeviceType.PRINTER
            textHas("playstation", "xbox", "nintendo", "steam deck") -> DeviceType.GAME_CONSOLE
            textHas("sonos", "homepod", "echo dot", "echo show", "echo pop", "echo studio", "amazon echo", "nest mini", "nest audio", "google home", "soundbar", "speaker") ||
                svcHas("_sonos._tcp") -> DeviceType.SPEAKER
            svcHas("_googlecast._tcp", "_androidtvremote2._tcp", "_amzn-wplay._tcp", "_roku-rcp._tcp") ||
                textHas("chromecast", "roku", "fire tv", "firetv", "apple tv", "appletv", "bravia", "webos", "smart tv", "nvidia shield", " tv") ||
                upnp.contains("mediarenderer") -> DeviceType.TV
            textHas("camera", "doorbell", "nvr", "hikvision", "reolink", "wyze cam") ||
                upnp.contains("networkcamera") -> DeviceType.CAMERA
            textHas("synology", "qnap", "diskstation", "readynas", "truenas", "unraid", " nas") ||
                svcHas("_adisk._tcp") || upnp.contains("mediaserver") -> DeviceType.NAS
            svcHas("_hap._tcp", "_homekit._tcp", "_matter._tcp", "_matterc._udp", "_hue._tcp") ||
                textHas("philips hue", "hue bridge", "smartthings", "tasmota", "shelly", "tuya", "thermostat", "smart plug") -> DeviceType.SMART_HOME
            textHas("iphone", "ipad", "android", "pixel", "galaxy", "oneplus") || 62078 in openPorts -> DeviceType.PHONE
            svcHas("_smb._tcp", "_afpovertcp._tcp", "_ssh._tcp", "_sftp-ssh._tcp", "_workstation._tcp", "_rfb._tcp", "_nvstream_dbd._tcp") ||
                textHas("macbook", "imac", "mac mini", "macmini", "desktop-", "laptop", "thinkpad", "windows") ||
                445 in openPorts || 22 in openPorts || 3389 in openPorts -> DeviceType.COMPUTER
            // AirPlay alone is offered by Apple TVs, HomePods and Macs alike; a receiver that
            // gave no other clue is most often a TV or streaming box.
            svcHas("_airplay._tcp", "_raop._tcp") -> DeviceType.TV
            svcHas("_companion-link._tcp") -> DeviceType.PHONE
            else -> DeviceType.UNKNOWN
        }
    }
}
