#version 150

uniform vec2 uViewport;
uniform vec4 uRect;
uniform float uRounding;
uniform float uShadowSize;
uniform float uShadowOpacity;

in vec2 vUv;
out vec4 fragColor;

float roundedRectDistance(vec2 point, vec4 rect, float radius) {
    vec2 halfSize = rect.zw * 0.5;
    vec2 center = rect.xy + halfSize;
    float safeRadius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(point - center) - halfSize + safeRadius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - safeRadius;
}

void main() {
    vec2 point = vec2(gl_FragCoord.x, uViewport.y - gl_FragCoord.y);
    float distance = roundedRectDistance(point, uRect, uRounding);
    if (distance <= 0.0) discard;

    float sigma = max(uShadowSize * 0.42, 0.001);
    float shadow = exp(-(distance * distance) / (2.0 * sigma * sigma)) * uShadowOpacity;
    if (shadow <= 0.002) discard;
    fragColor = vec4(0.0, 0.0, 0.0, shadow);
}
