package ru.lumastack.lumawork.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUserTest {
    @Test
    fun `legacy admin session without permissions keeps all permissions`() {
        val user = AppUser(
            id = "1",
            email = "admin@example.com",
            role = "admin",
            adminPermissions = null,
        )

        assertEquals(AdminPermission.entries.toSet(), user.adminPermissionSet)
    }

    @Test
    fun `explicit admin permissions are respected`() {
        val user = AppUser(
            id = "1",
            email = "admin@example.com",
            role = "admin",
            adminPermissions = listOf("users.view"),
        )

        assertTrue(user.can(AdminPermission.ViewUsers))
        assertFalse(user.can(AdminPermission.DeleteUsers))
    }

    @Test
    fun `ordinary user never gets admin permissions`() {
        val user = AppUser(
            id = "1",
            email = "user@example.com",
            role = "user",
            adminPermissions = listOf("users.view"),
        )

        assertTrue(user.adminPermissionSet.isEmpty())
    }
}

