package com.example

import com.example.data.HoloCommRepository
import com.example.model.HoloViewMode
import com.example.ui.render3d.HoloGeometryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun `verify 3d holographic geometry and deterministic room id`() {
        // Verify exact icosahedron geometry (12 vertices, 20 faces, 30 edges)
        assertEquals(12, HoloGeometryCache.icosahedronVertices.size)
        assertEquals(20, HoloGeometryCache.icosahedronFaces.size)
        assertEquals(30, HoloGeometryCache.icosahedronEdges.size)

        // Verify point-cloud globe and floating polyhedrons are populated
        assertTrue(HoloGeometryCache.globePoints.isNotEmpty())
        assertEquals(7, HoloGeometryCache.scenePolyhedrons.size)

        // Verify deterministic roomId generation for any two devices
        val room1 = HoloCommRepository.buildDeterministicRoomId("device_beta", "device_alpha")
        val room2 = HoloCommRepository.buildDeterministicRoomId("device_alpha", "device_beta")
        assertEquals("device_alpha_device_beta", room1)
        assertEquals(room1, room2)
        assertEquals(HoloViewMode.THREEJS_WEBGL, HoloViewMode.valueOf("THREEJS_WEBGL"))
    }
}
