@file:Suppress("NOTHING_TO_INLINE")

package dev.romainguy.shadowpointer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.romainguy.shadowpointer.ui.theme.ShadowPointerTheme
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShadowPointerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ShadowPointerDemo(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private const val PointerSquaredThreshold = 9.0f

@Composable
private fun ShadowPointerDemo(modifier: Modifier) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var pressure by remember { mutableFloatStateOf(0f) }

    val path = remember { Path() }
    var pathRef by remember { mutableIntStateOf(-1) }
    var lastPosition by remember { mutableStateOf(Offset.Zero) }

    val updateFinger = { size: IntSize, change: PointerInputChange ->
        val dimensions = size.toSize()
        val maxDimension = 1.0f / dimensions.maxDimension
        offsetX = (2.0f * change.position.x - dimensions.width) * maxDimension
        offsetY = (2.0f * change.position.y - dimensions.height) * maxDimension

        pressure = change.pressure
    }

    Box(modifier.fillMaxSize()) {
        ShadowPointer(
            fingerPosition = Float3(
                offsetX,
                offsetY,
                -0.1f
            ), // TODO: define this in dp?
            fingerDirection = Float3(0.18f, 0.22f, -0.12f),
            fingerLength = 0.9f, // TODO: define this in dp?
            fingerRadius = 0.06f, // TODO: define this in dp?
            lightPosition = Float3(0.0f, -1.0f, -1.3f), // TODO: define this in dp?
            lightAngle = 55.0f,//35.0f + (1.0f - pressure) * 40.0f,
            fadeDistance = 3.0f, // TODO: define this in dp?
            backgroundColor = Color.White,
            shadowColor = Color(0.0f, 0.0f, 0.0f, sqrt(pressure)),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        updateFinger(size, down)

                        path.rewind()
                        path.moveTo(down.position.x, down.position.y)
                        lastPosition = down.position

                        drag(down.id) { change ->
                            change.consume()
                            updateFinger(size, change)

                            val delta = change.position - lastPosition
                            if (delta.getDistanceSquared() >= PointerSquaredThreshold) {
                                path.lineTo(change.position.x, change.position.y)
                                lastPosition = change.position
                                pathRef++
                            }
                        }

                        pressure = 0f
                        pathRef = 0
                    }
                }
        )

        Canvas(Modifier.fillMaxSize()) {
            if (pathRef >= 0) {
                drawPath(
                    path,
                    Color.Black,
                    1.0f,
                    Stroke(5.dp.toPx(),
                    cap = StrokeCap.Round)
                )
            }
        }
    }
}
