/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.common.rendering

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.examples.java.common.rendering.ShaderUtil.checkGLError
import com.google.ar.core.examples.java.common.rendering.ShaderUtil.loadGLShader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * This class renders the AR background from the camera feed. It creates and hosts the texture given
 * to ARCore to be filled with the camera image.
 */
class BackgroundRenderer {

    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    private var cameraProgram = 0
    private var depthProgram = 0

    private var cameraPositionAttrib = 0
    private var cameraTexCoordAttrib = 0
    private var cameraTextureUniform = 0
    var textureId: Int = -1
        private set
    private var suppressTimestampZeroRendering = true

    private var depthPositionAttrib = 0
    private var depthTexCoordAttrib = 0
    private var depthTextureUniform = 0
    private var depthTextureId = -1

    /**
     * Allocates and initializes OpenGL resources needed by the background renderer. Must be called on
     * the OpenGL thread, typically in [GLSurfaceView.Renderer.onSurfaceCreated].
     *
     * @param context Needed to access shader source.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun createOnGlThread(context: Context, depthTextureId: Int = -1) {
        // Generate the background texture.
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val numVertices = 4
        if (numVertices != QUAD_COORDS.size / COORDS_PER_VERTEX) {
            throw RuntimeException("Unexpected number of vertices in BackgroundRenderer.")
        }

        val bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        bbCoords.order(ByteOrder.nativeOrder())
        quadCoords = bbCoords.asFloatBuffer()
        quadCoords.put(QUAD_COORDS)
        quadCoords.position(0)

        val bbTexCoordsTransformed =
            ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE)
        bbTexCoordsTransformed.order(ByteOrder.nativeOrder())
        quadTexCoords = bbTexCoordsTransformed.asFloatBuffer()

        // Load render camera feed shader.
        run {
            val vertexShader =
                loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER_NAME)
            val fragmentShader =
                loadGLShader(
                    TAG, context, GLES20.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER_NAME
                )

            cameraProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(cameraProgram, vertexShader)
            GLES20.glAttachShader(cameraProgram, fragmentShader)
            GLES20.glLinkProgram(cameraProgram)
            GLES20.glUseProgram(cameraProgram)
            cameraPositionAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
            cameraTexCoordAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
            checkGLError(TAG, "Program creation")

            cameraTextureUniform = GLES20.glGetUniformLocation(cameraProgram, "sTexture")
            checkGLError(TAG, "Program parameters")
        }

        // Load render depth map shader.
        run {
            val vertexShader =
                loadGLShader(
                    TAG, context, GLES20.GL_VERTEX_SHADER, DEPTH_VISUALIZER_VERTEX_SHADER_NAME
                )
            val fragmentShader =
                loadGLShader(
                    TAG, context, GLES20.GL_FRAGMENT_SHADER, DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME
                )

            depthProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(depthProgram, vertexShader)
            GLES20.glAttachShader(depthProgram, fragmentShader)
            GLES20.glLinkProgram(depthProgram)
            GLES20.glUseProgram(depthProgram)
            depthPositionAttrib = GLES20.glGetAttribLocation(depthProgram, "a_Position")
            depthTexCoordAttrib = GLES20.glGetAttribLocation(depthProgram, "a_TexCoord")
            checkGLError(TAG, "Program creation")

            depthTextureUniform = GLES20.glGetUniformLocation(depthProgram, "u_DepthTexture")
            checkGLError(TAG, "Program parameters")
        }

        this.depthTextureId = depthTextureId
    }

    fun suppressTimestampZeroRendering(suppressTimestampZeroRendering: Boolean) {
        this.suppressTimestampZeroRendering = suppressTimestampZeroRendering
    }

    /**
     * Draws the AR background image.
     *
     * @param frame The current `Frame` as returned by [Session.update].
     * @param debugShowDepthMap Toggles whether to show the live camera feed or latest depth image.
     */
    @JvmOverloads
    fun draw(frame: Frame, debugShowDepthMap: Boolean = false) {
        // If display rotation changed (also includes view size change), we need to re-query the uv
        // coordinates for the screen rect, as they may have changed as well.
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }

        if (frame.timestamp == 0L && suppressTimestampZeroRendering) {
            // Suppress rendering if the camera did not produce the first frame yet.
            return
        }

        draw(debugShowDepthMap)
    }

    /**
     * Draws the camera image using the currently configured [quadTexCoords].
     */
    fun draw(
        imageWidth: Int, imageHeight: Int, screenAspectRatio: Float, cameraToDisplayRotation: Int
    ) {
        // Crop the camera image to fit the screen aspect ratio.
        val imageAspectRatio = imageWidth.toFloat() / imageHeight
        val croppedWidth: Float
        val croppedHeight: Float
        if (screenAspectRatio < imageAspectRatio) {
            croppedWidth = imageHeight * screenAspectRatio
            croppedHeight = imageHeight.toFloat()
        } else {
            croppedWidth = imageWidth.toFloat()
            croppedHeight = imageWidth / screenAspectRatio
        }

        val u = (imageWidth - croppedWidth) / imageWidth * 0.5f
        val v = (imageHeight - croppedHeight) / imageHeight * 0.5f
        val texCoordTransformed = when (cameraToDisplayRotation) {
            90 -> floatArrayOf(1 - u, 1 - v, 1 - u, v, u, 1 - v, u, v)
            180 -> floatArrayOf(1 - u, v, u, v, 1 - u, 1 - v, u, 1 - v)
            270 -> floatArrayOf(u, v, u, 1 - v, 1 - u, v, 1 - u, 1 - v)
            0 -> floatArrayOf(u, 1 - v, 1 - u, 1 - v, u, v, 1 - u, v)
            else -> throw IllegalArgumentException("Unhandled rotation: $cameraToDisplayRotation")
        }

        // Write image texture coordinates.
        quadTexCoords.position(0)
        quadTexCoords.put(texCoordTransformed)

        draw(false)
    }

    /**
     * Draws the camera background image using the currently configured [quadTexCoords].
     */
    private fun draw(debugShowDepthMap: Boolean) {
        quadTexCoords.position(0)

        // Disable depth testing for the screen quad.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        if (debugShowDepthMap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
            GLES20.glUseProgram(depthProgram)
            GLES20.glUniform1i(depthTextureUniform, 0)

            GLES20.glVertexAttribPointer(
                depthPositionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords
            )
            GLES20.glVertexAttribPointer(
                depthTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords
            )
            GLES20.glEnableVertexAttribArray(depthPositionAttrib)
            GLES20.glEnableVertexAttribArray(depthTexCoordAttrib)
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUseProgram(cameraProgram)
            GLES20.glUniform1i(cameraTextureUniform, 0)

            GLES20.glVertexAttribPointer(
                cameraPositionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords
            )
            GLES20.glVertexAttribPointer(
                cameraTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords
            )
            GLES20.glEnableVertexAttribArray(cameraPositionAttrib)
            GLES20.glEnableVertexAttribArray(cameraTexCoordAttrib)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex arrays.
        if (debugShowDepthMap) {
            GLES20.glDisableVertexAttribArray(depthPositionAttrib)
            GLES20.glDisableVertexAttribArray(depthTexCoordAttrib)
        } else {
            GLES20.glDisableVertexAttribArray(cameraPositionAttrib)
            GLES20.glDisableVertexAttribArray(cameraTexCoordAttrib)
        }

        // Restore depth state.
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        checkGLError(TAG, "BackgroundRendererDraw")
    }

    companion object {
        private val TAG: String = BackgroundRenderer::class.java.simpleName

        // Shader names.
        private const val CAMERA_VERTEX_SHADER_NAME = "shaders/screenquad.vert"
        private const val CAMERA_FRAGMENT_SHADER_NAME = "shaders/screenquad.frag"
        private const val DEPTH_VISUALIZER_VERTEX_SHADER_NAME =
            "shaders/background_show_depth_color_visualization.vert"
        private const val DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME =
            "shaders/background_show_depth_color_visualization.frag"

        private const val COORDS_PER_VERTEX = 2
        private const val TEXCOORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, +1.0f, -1.0f, -1.0f, +1.0f, +1.0f, +1.0f,
        )
    }
}
