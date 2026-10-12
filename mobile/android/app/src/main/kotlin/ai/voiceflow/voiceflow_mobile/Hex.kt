package ai.voiceflow.voiceflow_mobile

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
