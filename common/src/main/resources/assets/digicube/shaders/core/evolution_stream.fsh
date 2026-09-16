#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    vec2 p = abs(texCoord0 * 2.0 - 1.0);
    // The quad has twice the old halo margin; doubling UV distance preserves
    // the approved inner footprint. Correct its aspect ratio for equal edge width.
    vec2 q = (p * 2.0 - vec2(0.73)) * vec2(1.3 / 0.45, 1.0);
    float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
    float aa = max(fwidth(distance), 0.008);
    float outline = 1.0 - smoothstep(0.035, 0.035 + aa, abs(distance));
    float fill = 1.0 - smoothstep(-aa, aa, distance);
    // A local halo within the existing quad; fade it out before the quad boundary.
    float glow = exp(-abs(distance) * 6.5)
        * (1.0 - smoothstep(0.9, 1.0, max(p.x, p.y)));
    float strength = min(1.0, fill * 0.20 + outline * 0.96 + glow * 0.45);
    vec4 color = vec4(mix(vertexColor.rgb, vec3(1.0), outline),
        vertexColor.a * strength) * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, vec4(0.0));
}
