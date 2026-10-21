package com.example.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class DeviceActionManager(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "DeviceActionManager"

        private val SUPPORTED_APPS = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps"
        )
    }

    /**
     * Executes tool call requested by Gemini Live or REST API
     */
    fun executeTool(toolName: String, args: JSONObject): JSONObject {
        log("Executing tool: $toolName with arguments: $args")
        return when (toolName) {
            "openWhatsApp" -> openWhatsApp()
            "openApp" -> {
                val appName = args.optString("appName", "")
                openApp(appName)
            }
            "openWebsite", "openUrl" -> {
                val url = args.optString("url", args.optString("website", ""))
                openWebsite(url)
            }
            "makeCall" -> {
                val phoneNumber = args.optString("phoneNumber", args.optString("phone", ""))
                makeCall(phoneNumber)
            }
            "callContact" -> {
                val contactName = args.optString("contactName", args.optString("name", ""))
                callContact(contactName)
            }
            else -> {
                val err = JSONObject()
                err.put("success", false)
                err.put("error", "Unknown or unsupported tool action: $toolName")
                log("Unsupported tool: $toolName")
                err
            }
        }
    }

    fun openWhatsApp(): JSONObject {
        val response = JSONObject()
        try {
            val pm = context.packageManager
            var launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
            if (launchIntent == null) {
                launchIntent = pm.getLaunchIntentForPackage("com.whatsapp.w4b")
            }

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                response.put("success", true)
                response.put("action", "openWhatsApp")
                response.put("message", "WhatsApp launched successfully")
                log("WhatsApp launched successfully")
            } else {
                // Fallback to WhatsApp URL intent
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (webIntent.resolveActivity(pm) != null) {
                    context.startActivity(webIntent)
                    response.put("success", true)
                    response.put("action", "openWhatsApp")
                    response.put("message", "Opened WhatsApp via browser intent")
                } else {
                    response.put("success", false)
                    response.put("action", "openWhatsApp")
                    response.put("error", "WhatsApp is not installed on this device")
                    log("WhatsApp not installed on device")
                }
            }
        } catch (e: Exception) {
            response.put("success", false)
            response.put("action", "openWhatsApp")
            response.put("error", "Failed to launch WhatsApp: ${e.message}")
            log("WhatsApp launch failed: ${e.message}")
        }
        return response
    }

    fun openApp(appName: String): JSONObject {
        val response = JSONObject()
        val normalizedName = appName.trim().lowercase()

        // Handle special system intents first
        when {
            normalizedName.contains("camera") -> {
                return try {
                    val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(cameraIntent)
                    response.put("success", true)
                    response.put("action", "openApp")
                    response.put("appName", "Camera")
                } catch (e: Exception) {
                    response.put("success", false)
                    response.put("error", "Unable to open Camera: ${e.message}")
                }
            }
            normalizedName.contains("setting") -> {
                return try {
                    val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    response.put("success", true)
                    response.put("action", "openApp")
                    response.put("appName", "Settings")
                } catch (e: Exception) {
                    response.put("success", false)
                    response.put("error", "Unable to open Settings: ${e.message}")
                }
            }
            normalizedName.contains("clock") || normalizedName.contains("alarm") -> {
                return try {
                    val clockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(clockIntent)
                    response.put("success", true)
                    response.put("action", "openApp")
                    response.put("appName", "Clock")
                } catch (e: Exception) {
                    response.put("success", false)
                    response.put("error", "Unable to open Clock: ${e.message}")
                }
            }
        }

        // Match against supported packages
        val packageName = SUPPORTED_APPS.entries.firstOrNull { normalizedName.contains(it.key) }?.value
        if (packageName == null) {
            response.put("success", false)
            response.put("error", "App '$appName' is not in the supported applications allowlist")
            log("App not in allowlist: $appName")
            return response
        }

        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                response.put("success", true)
                response.put("action", "openApp")
                response.put("appName", appName)
                response.put("packageName", packageName)
                log("Opened app $appName ($packageName)")
            } else {
                response.put("success", false)
                response.put("error", "$appName is not installed on this device")
                log("App $appName ($packageName) not installed")
            }
            response
        } catch (e: Exception) {
            response.put("success", false)
            response.put("error", "Failed to open $appName: ${e.message}")
            log("Failed to open app $appName: ${e.message}")
            response
        }
    }

    fun openWebsite(url: String): JSONObject {
        val response = JSONObject()
        if (url.isBlank()) {
            response.put("success", false)
            response.put("error", "Website URL cannot be empty")
            return response
        }

        var sanitizedUrl = url.trim()
        if (!sanitizedUrl.startsWith("http://") && !sanitizedUrl.startsWith("https://")) {
            sanitizedUrl = "https://$sanitizedUrl"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanitizedUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            response.put("success", true)
            response.put("action", "openWebsite")
            response.put("url", sanitizedUrl)
            log("Opened website: $sanitizedUrl")
            response
        } catch (e: Exception) {
            response.put("success", false)
            response.put("error", "Failed to open URL $sanitizedUrl: ${e.message}")
            log("Failed to open URL: ${e.message}")
            response
        }
    }

    fun makeCall(phoneNumber: String): JSONObject {
        val response = JSONObject()
        val cleaned = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleaned.length < 3) {
            response.put("success", false)
            response.put("error", "Invalid phone number: $phoneNumber")
            return response
        }

        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            response.put("success", true)
            response.put("action", "makeCall")
            response.put("phoneNumber", cleaned)
            response.put("message", "Opened dialer for $cleaned")
            log("Dialer opened for $cleaned")
            response
        } catch (e: Exception) {
            response.put("success", false)
            response.put("error", "Failed to open dialer: ${e.message}")
            log("Dialer failed: ${e.message}")
            response
        }
    }

    data class ContactMatch(val name: String, val phone: String)

    fun callContact(contactName: String): JSONObject {
        val response = JSONObject()
        if (contactName.isBlank()) {
            response.put("success", false)
            response.put("error", "Contact name cannot be empty")
            return response
        }

        // Check contacts permission
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            response.put("success", false)
            response.put("permissionRequired", "READ_CONTACTS")
            response.put("error", "Contacts permission is not granted. Please grant contacts permission in Settings to call by contact name.")
            log("Contacts permission not granted for callContact")
            return response
        }

        val matches = mutableListOf<ContactMatch>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val queryLower = contactName.trim().lowercase()

                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: ""
                    val number = it.getString(numberIndex) ?: ""
                    if (name.lowercase().contains(queryLower) && number.isNotBlank()) {
                        // Prevent exact duplicate phone numbers
                        if (matches.none { m -> m.phone == number }) {
                            matches.add(ContactMatch(name, number))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("Contacts query error: ${e.message}")
            response.put("success", false)
            response.put("error", "Error reading contacts: ${e.message}")
            return response
        }

        when {
            matches.isEmpty() -> {
                response.put("success", false)
                response.put("error", "No contact found matching '$contactName' on your device")
                log("No contact match found for '$contactName'")
            }
            matches.size == 1 -> {
                val match = matches.first()
                log("Found single contact match: ${match.name} (${match.phone})")
                val dialRes = makeCall(match.phone)
                response.put("success", dialRes.optBoolean("success", true))
                response.put("action", "callContact")
                response.put("name", match.name)
                response.put("phoneNumber", match.phone)
            }
            else -> {
                // Multiple matches! Prompt user to clarify
                log("Found ${matches.size} contacts for '$contactName'")
                response.put("success", false)
                response.put("error", "Multiple contacts found")
                val jsonMatches = JSONArray()
                matches.take(5).forEach { m ->
                    val obj = JSONObject()
                    obj.put("name", m.name)
                    obj.put("number", m.phone)
                    jsonMatches.put(obj)
                }
                response.put("matches", jsonMatches)
                response.put("clarificationMessage", "I found ${matches.size} contacts named '$contactName'. Which one would you like to call?")
            }
        }
        return response
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog(message)
    }
}
