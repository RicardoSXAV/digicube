#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    bool halo = texCoord0.x > 1.5;
    vec2 uv = vec2(texCoord0.x - (halo ? 2.0 : 0.0), texCoord0.y);
    float strength;
    if (halo) {
        vec2 d = abs(uv * 2.0 - 1.0);
        float falloff = max(0.0, 1.0 - length(d));
        strength = falloff * falloff * falloff;
    } else {
        vec2 edge = min(uv, 1.0 - uv);
        float aa = max(max(fwidth(uv.x), fwidth(uv.y)), 0.005);
        float border = 1.0 - smoothstep(0.045, 0.045 + aa, min(edge.x, edge.y));
        strength = 0.055 + border * 0.7;
    }
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * strength) * ColorModulator;
    // Additive light fades toward black in fog, never toward the world's fog colour.
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, vec4(0.0));
}
