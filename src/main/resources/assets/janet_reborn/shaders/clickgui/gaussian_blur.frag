#version 150

uniform sampler2D uTexture;
uniform vec2 uTexelStep;
uniform int uKernelRadius;
uniform float uWeights[25];

in vec2 vUv;
out vec4 fragColor;

void main() {
    vec4 color = texture(uTexture, vUv) * uWeights[0];
    for (int offset = 1; offset <= 24; ++offset) {
        if (offset > uKernelRadius) break;
        vec2 sampleOffset = uTexelStep * float(offset);
        color += texture(uTexture, vUv + sampleOffset) * uWeights[offset];
        color += texture(uTexture, vUv - sampleOffset) * uWeights[offset];
    }
    fragColor = color;
}
