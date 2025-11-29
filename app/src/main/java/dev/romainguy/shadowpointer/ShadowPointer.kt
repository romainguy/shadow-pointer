@file:Suppress("NOTHING_TO_INLINE")

package dev.romainguy.shadowpointer

import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import org.intellij.lang.annotations.Language
import kotlin.math.cos
import kotlin.math.sqrt

@Language("AGSL")
private const val CapsuleSoftShadowShader = """
layout(color) uniform half4 backgroundColor;
layout(color) uniform half4 shadowColor;
uniform vec3 fingerPosition;
uniform float fingerSquareRadius;
uniform vec3 fingerDirection;
uniform float fingerLength;
uniform vec3 lightConeDirection;
uniform vec2 lightConeAngle;
uniform vec2 size;
uniform vec4 fadeDistance;

// The closest value of PI that will fit in fp32
const float PI = 3.1415927;

float sq(float x) {
    return x * x;
}

float acosFast(float x) {
    // Lagarde 2014, "Inverse trigonometric functions GPU optimization for AMD GCN architecture"
    // This is the approximation of degree 1, with a max absolute error of 9.0x10^-3
    float y = abs(x);
    float p = -0.1565827 * y + 1.570796;
    p *= sqrt(1.0 - y);
    return x >= 0.0 ? p : PI - p;
}

float acosFastPositive(float x) {
    // Lagarde 2014, "Inverse trigonometric functions GPU optimization for AMD GCN architecture"
    float p = -0.1565827 * x + 1.570796;
    return p * sqrt(1.0 - x);
}

float sphericalCapsIntersection(float cosCap1, float cosCap2, float cap2, float cosDistance) {
    // Oat and Sander 2007, "Ambient Aperture Lighting"
    // Approximation mentioned by Jimenez et al. 2016
    float r1 = acosFastPositive(cosCap1);
    float r2 = cap2;
    float d  = acosFast(cosDistance);

    // We work with cosine angles, replace the original paper's use of
    // cos(min(r1, r2)) with max(cosCap1, cosCap2)
    // We also remove a multiplication by 2 * PI to simplify the computation
    // since we divide by 2 * PI at the call site

    if (min(r1, r2) <= max(r1, r2) - d) {
        return 1.0 - max(cosCap1, cosCap2);
    } else if (r1 + r2 <= d) {
        return 0.0;
    }

    float delta = abs(r1 - r2);
    float x = 1.0 - saturate((d - delta) / max(r1 + r2 - delta, 0.0001));
    // simplified smoothstep()
    float area = sq(x) * (-2.0 * x + 3.0);
    return area * (1.0 - max(cosCap1, cosCap2));
}

float directionalOcclusionSphere(
    in vec3 pos,
    in vec4 sphere,
    in vec3 coneDirection,
    in vec2 coneAngle
) {
    vec3 occluder = sphere.xyz - pos;
    float occluderLength2 = dot(occluder, occluder);
    vec3 occluderDir = occluder * inversesqrt(occluderLength2);

    float cosPhi = dot(occluderDir, coneDirection);
    float cosTheta = sqrt(occluderLength2 / (sphere.w + occluderLength2));

    float occlusion =
        sphericalCapsIntersection(cosTheta, coneAngle.x, coneAngle.y, cosPhi) / (1.0 - coneAngle.x);
    return occlusion;
}

float directionalOcclusionCapsule(
    in vec3 pos,
    in vec3 capsuleA,
    in vec3 capsuleB,
    in float capsuleRadius,
    in vec3 coneDirection,
    in vec2 coneAngle
) {
    vec3 Ld = capsuleB - capsuleA;
    vec3 L0 = capsuleA - pos;
    float a = dot(coneDirection, Ld);
    float t = saturate(dot(L0, a * coneDirection - Ld) / (dot(Ld, Ld) - a * a));
    vec3 posToRay = capsuleA + t * Ld;

    return directionalOcclusionSphere(pos, vec4(posToRay, capsuleRadius), coneDirection, coneAngle);
}

half4 main(float2 fragCoord) {
    vec3 position = vec3((2.0 * fragCoord - size) / vec2(max(size.x, size.y)), 0.0);
    vec3 fingerEnd = fingerPosition + fingerDirection * fingerLength; // for a second phalanx: * 0.3;
    float occlusion = directionalOcclusionCapsule(
        position,
        fingerPosition,
        fingerEnd,
        fingerSquareRadius,
        lightConeDirection,
        lightConeAngle
    );
    // // Second phalanx
    // occlusion = min(occlusion, directionalocclusionCapsule(
    //     position,
    //     fingerEnd,
    //     fingerEnd + normalize(vec3(fingerDirection.x * 1.1, fingerDirection.y * 2.0, fingerDirection.z * 0.5)) * fingerLength * 0.7,
    //     fingerSquareRadius,
    //     lightConeDirection,
    //     lightConeAngle
    // ));
    vec3 posToLight = fadeDistance.xyz - position;
    float distanceSquare = dot(posToLight, posToLight);
    float factor = distanceSquare * fadeDistance.w;
    float smoothFactor = max(1.0 - factor * factor, 0.0);
    float attenuation = (smoothFactor * smoothFactor) / max(distanceSquare, 1e-4);
    attenuation *= occlusion;
    return shadowColor * attenuation + (1.0 - attenuation * shadowColor.a) * backgroundColor;
}
"""

data class Float3(
    val x: Float,
    val y: Float,
    val z: Float
)

private fun Float3.magnitude(): Float = sqrt(x * x + y * y + z * z)

private operator fun Float3.minus(v: Float3) = Float3(x - v.x, y - v.y, z - v.z)

@Composable
fun ShadowPointer(
    fingerPosition: Float3,
    fingerDirection: Float3,
    fingerLength: Float,
    fingerRadius: Float,
    lightPosition: Float3,
    lightAngle: Float,
    fadeDistance: Float,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White,
    shadowColor: Color = Color.Black
) {
    val shader = remember { RuntimeShader(CapsuleSoftShadowShader) }
    val brush = remember(
        fingerPosition,
        fingerDirection,
        fingerLength,
        fingerRadius,
        lightPosition,
        lightAngle,
        backgroundColor,
        shadowColor
    ) {
        ShaderBrush(shader)
    }

    val fingerDirectionMagnitude = 1.0f / fingerDirection.magnitude()
    val lightDirection = lightPosition - fingerPosition
    val lightDirectionMagnitude = 1.0f / lightDirection.magnitude()

    // TODO: Pressure, orientation
    shader.setColorUniform("backgroundColor", backgroundColor.toArgb())
    shader.setColorUniform("shadowColor", shadowColor.toArgb())

    shader.setFloatUniform(
        "fingerPosition",
        fingerPosition.x,
        fingerPosition.y,
        fingerPosition.z
    )
    shader.setFloatUniform(
        "fingerDirection",
        fingerDirection.x * fingerDirectionMagnitude,
        fingerDirection.y * fingerDirectionMagnitude,
        fingerDirection.z * fingerDirectionMagnitude
    )
    shader.setFloatUniform("fingerLength", fingerLength)
    shader.setFloatUniform("fingerSquareRadius", fingerRadius * fingerRadius)

    shader.setFloatUniform(
        "lightConeDirection",
        lightDirection.x * lightDirectionMagnitude,
        lightDirection.y * lightDirectionMagnitude,
        lightDirection.z * lightDirectionMagnitude
    )
    val coneAngle = radians(lightAngle) * 0.5f
    shader.setFloatUniform("lightConeAngle", cos(coneAngle), coneAngle)

    shader.setFloatUniform(
        "fadeDistance",
        lightPosition.x,
        lightPosition.y,
        lightPosition.z,
        1.0f / (fadeDistance * fadeDistance)
    )

    Canvas(modifier) {
        shader.setFloatUniform("size", size.width, size.height)
        drawRect(brush, Offset.Zero, size)
    }
}

private const val FloatPi = 3.1415927f

private inline fun radians(degrees: Float) = degrees * (FloatPi / 180.0f)
