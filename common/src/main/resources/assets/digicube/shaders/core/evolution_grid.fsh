#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float a=vertexColor.a*255.0;
    float threshold=fract(dot(floor(gl_FragCoord.xy),vec2(0.754877666,0.569840296)));
    if(a<127.5 && threshold>=a/127.0)discard;
    if(a>=127.5 && a<254.5 && threshold<(a-128.0)/126.0)discard;
    vec2 d=abs(fract(texCoord0+0.5)-0.5);
    vec2 aa=max(fwidth(texCoord0),vec2(0.001));
    vec2 lines=1.0-smoothstep(vec2(0.025),vec2(0.025)+aa,d);
    float line=max(lines.x,lines.y);
    // Fade subpixel detail instead of turning a distant silhouette into a white cloud.
    line*=1.0-smoothstep(0.35,0.8,max(aa.x,aa.y));
    vec3 fill=vertexColor.rgb*0.74;
    vec4 color=vec4(mix(fill,mix(vertexColor.rgb,vec3(1.0),0.85),line),1.0)*ColorModulator;
    fragColor=apply_fog(color,sphericalVertexDistance,cylindricalVertexDistance,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);
}
