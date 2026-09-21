package com.nadeem.apkscope.poc.apkrepack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FridaCommandPolicyTest {
    @Test
    fun helloMustMatchTheActiveTargetPackage() {
        assertTrue(FridaCommandPolicy.acceptsHello("com.example.target", "token", "com.example.target", "token"))
        assertFalse(FridaCommandPolicy.acceptsHello("com.example.target", "token", "com.example.other", "token"))
    }

    @Test
    fun helloMustMatchThePerRepackToken() {
        assertFalse(FridaCommandPolicy.acceptsHello("com.example.target", "token", "com.example.target", "wrong"))
        assertFalse(FridaCommandPolicy.acceptsHello("com.example.target", "token", "com.example.target", ""))
    }

    @Test
    fun helloIsRejectedWhenTheMonitorIsNotBoundToAConcreteSession() {
        assertFalse(FridaCommandPolicy.acceptsHello(null, "token", "com.example.target", "token"))
        assertFalse(FridaCommandPolicy.acceptsHello("com.example.target", null, "com.example.target", "token"))
    }

    @Test
    fun commandTargetCannotBeSelectedWhenNoTargetIsBound() {
        assertTrue(FridaCommandPolicy.acceptsCommandTarget("com.example.target", "com.example.target"))
        assertFalse(FridaCommandPolicy.acceptsCommandTarget("com.example.target", "com.example.other"))
        assertFalse(FridaCommandPolicy.acceptsCommandTarget(null, "com.example.target"))
    }
}
