#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;

out vec4 fragColor;

// Volcanic voxel clouds: shaded like vanilla clouds. They take the environmental fog (rain, ash haze) like terrain,
// but like clouds they stay visible beyond the chunk render distance, fading out over a longer range.
void main() {
    vec4 color = vertexColor * ColorModulator;
    if (color.a < 0.01) {
        discard;
    }
    float far = max(FogRenderDistanceEnd * 2.5, FogCloudsEnd);
    float fog = max(linear_fog_value(sphericalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd * 1.6),
                    linear_fog_value(cylindricalVertexDistance, FogRenderDistanceStart * 1.5, far));
    fragColor = vec4(mix(color.rgb, FogColor.rgb, fog * FogColor.a), color.a);
}
