#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float strength;
    if (texCoord0.x >= 4.0) {
        vec2 uv = vec2(texCoord0.x-4.0, texCoord0.y);
        float edge = min(min(uv.x,1.0-uv.x),min(uv.y,1.0-uv.y));
        strength = .25 + .65*(1.0-smoothstep(.06,.11,edge));
    } else if (texCoord0.x >= 2.0) {
        float r = length(vec2(texCoord0.x-2.0,texCoord0.y)*2.0-1.0);
        strength = pow(max(0.0,1.0-r),2.0);
    } else {
        float x = abs(texCoord0.x*2.0-1.0);
        strength = (exp(-x*x*240.0)*.75 + exp(-x*x*8.0)*.15)
                * (1.0-smoothstep(.78,1.0,texCoord0.y));
    }
    vec4 color = vec4(vertexColor.rgb, vertexColor.a*strength)*ColorModulator;
    // Keep environmental fog (water/lava/blindness), but the locator has its own 448–512 block fade.
    fragColor = apply_fog(color,sphericalVertexDistance,cylindricalVertexDistance,
        FogEnvironmentalStart,FogEnvironmentalEnd,1e7,1e8,vec4(0.0));
}
