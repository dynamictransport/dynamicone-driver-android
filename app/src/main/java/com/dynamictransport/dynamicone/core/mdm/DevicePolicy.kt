package com.dynamictransport.dynamicone.core.mdm

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.dynamictransport.dynamicone.core.common.AppLogger
import com.dynamictransport.dynamicone.domain.model.DevicePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app as device admin and device owner (docs/29 §1). The test phone is made device owner with
 * `adb shell dpm set-device-owner com.dynamictransport.dynamicone/.core.mdm.DeviceOwnerReceiver`; the class name is
 * part of that command and must not move.
 */
class DeviceOwnerReceiver : DeviceAdminReceiver()

/** The five restrictions of the policy (docs/29 §3.5). */
enum class DeviceRestriction(val key: String) {
    FACTORY_RESET(UserManager.DISALLOW_FACTORY_RESET),
    UNKNOWN_SOURCES(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
    USB_FILE_TRANSFER(UserManager.DISALLOW_USB_FILE_TRANSFER),
    SAFE_BOOT(UserManager.DISALLOW_SAFE_BOOT),
    ADD_USER(UserManager.DISALLOW_ADD_USER),
}

/** `DevicePolicyManager` behind an interface. Every call may throw `SecurityException` when the app lacks the role. */
interface DevicePolicyController {
    val isDeviceOwner: Boolean

    /** Device admin (or owner): enough to lock the screen. */
    val isAdminActive: Boolean

    fun lockNow()

    fun reboot()

    /** Factory reset. */
    fun wipeData()

    fun setRestriction(restriction: DeviceRestriction, blocked: Boolean)

    fun setUninstallBlocked(blocked: Boolean)

    /** Grants one of the app's own runtime permissions without asking; device owner only. */
    fun grantOwnPermission(permission: String): Boolean
}

@Singleton
class AndroidDevicePolicyController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DevicePolicyController {

    private val manager: DevicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, DeviceOwnerReceiver::class.java)

    override val isDeviceOwner: Boolean get() = manager.isDeviceOwnerApp(context.packageName)

    override val isAdminActive: Boolean get() = manager.isAdminActive(admin)

    override fun lockNow() = manager.lockNow()

    override fun reboot() = manager.reboot(admin)

    override fun wipeData() {
        // Android 14+ refuses wipeData() on the main user for apps that target it; wipeDevice() is its replacement.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) manager.wipeDevice(0) else manager.wipeData(0)
    }

    override fun setRestriction(restriction: DeviceRestriction, blocked: Boolean) {
        if (blocked) manager.addUserRestriction(admin, restriction.key) else manager.clearUserRestriction(admin, restriction.key)
    }

    override fun setUninstallBlocked(blocked: Boolean) = manager.setUninstallBlocked(admin, context.packageName, blocked)

    override fun grantOwnPermission(permission: String): Boolean =
        manager.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
}

/** Applies the server's policy and lifts it again when the device is retired (docs/29 §2, §3.5). */
@Singleton
class DevicePolicyApplier @Inject constructor(
    private val controller: DevicePolicyController,
    private val state: DeviceManagementStateStore,
    private val logger: AppLogger,
) {
    /**
     * A device owner holds the permissions the office's commands need without the driver's tap: notifications
     * (messages, ring, the session notice) and the serial number.
     */
    fun grantOwnerPermissions() {
        if (!controller.isDeviceOwner) return
        OWNER_PERMISSIONS.forEach { permission -> guarded("grant") { controller.grantOwnPermission(permission) } }
    }

    /** While enrolled, a device owner cannot be uninstalled. */
    fun onEnrolled() {
        if (!controller.isDeviceOwner) return
        guarded("uninstall_block") { controller.setUninstallBlocked(true) }
        grantOwnerPermissions()
    }

    /** @return true when the restrictions are in force; only then is the version reported as applied. */
    suspend fun apply(policy: DevicePolicy): Boolean {
        if (!controller.isDeviceOwner) return false
        val applied = restrictions(policy).map { (restriction, blocked) ->
            guarded(restriction.name) { controller.setRestriction(restriction, blocked) }
        }.all { it }
        if (applied) state.setPolicyVersionApplied(policy.version)
        return applied
    }

    fun clear() {
        if (!controller.isDeviceOwner) return
        DeviceRestriction.entries.forEach { restriction -> guarded(restriction.name) { controller.setRestriction(restriction, false) } }
        guarded("uninstall_block") { controller.setUninstallBlocked(false) }
    }

    private fun guarded(what: String, call: () -> Unit): Boolean = try {
        call()
        true
    } catch (e: RuntimeException) {
        // SecurityException (role lost meanwhile) or a vendor's refusal: reported, never fatal.
        logger.warn("device policy $what", e)
        false
    }

    companion object {
        fun restrictions(policy: DevicePolicy): Map<DeviceRestriction, Boolean> = mapOf(
            DeviceRestriction.FACTORY_RESET to policy.blockFactoryReset,
            DeviceRestriction.UNKNOWN_SOURCES to policy.blockUnknownSources,
            DeviceRestriction.USB_FILE_TRANSFER to policy.blockUsbFileTransfer,
            DeviceRestriction.SAFE_BOOT to policy.blockSafeBoot,
            DeviceRestriction.ADD_USER to policy.blockAddUser,
        )

        private val OWNER_PERMISSIONS: List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_PHONE_STATE)
        }
    }
}
