package shit.nilore.render.shader;

import java.util.HashMap;
import lombok.Getter;
import lombok.Generated;

public enum ShaderSource {
    SHADER_0("empty", ""),
    SHADER_1("vertex.vsh", "#version 150\n\nin vec3 Position;\nin vec2 UV0;\n\nuniform mat4 ModelViewMat;\nuniform mat4 ProjMat;\n\nout vec2 TexCoord;\n\nvoid main() {\n    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n    TexCoord = UV0;\n}\n"),
    SHADER_2("vertex_color.vsh", "#version 150\n\nin vec3 Position;\nin vec2 UV0;\nin vec4 Color;\n\nuniform mat4 ModelViewMat;\nuniform mat4 ProjMat;\n\nout vec2 TexCoord;\nout vec4 FragColor;\n\nvoid main() {\n    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n    TexCoord = UV0;\n    FragColor = Color;\n}\n"),
    SHADER_3("common.glsl", "float rdist(vec2 pos, vec2 size, vec4 radius) {\n    radius.xy = (pos.x > 0.0) ? radius.xy : radius.wz;\n    radius.x  = (pos.y > 0.0) ? radius.x : radius.y;\n\n    vec2 v = abs(pos) - size + radius.x;\n    return min(max(v.x, v.y), 0.0) + length(max(v, 0.0)) - radius.x;\n}\n\nfloat ralpha(vec2 size, vec2 coord, vec4 radius, float smoothness) {\n    vec2 center = size * 0.5;\n    float dist = rdist(center - (coord * size), center - 1.0, radius);\n    return 1.0 - smoothstep(1.0 - smoothness, 1.0, dist);\n}\n\nconst vec2[4] RECT_VERTICES_COORDS = vec2[] (\n    vec2(0.0, 0.0),\n    vec2(0.0, 1.0),\n    vec2(1.0, 1.0),\n    vec2(1.0, 0.0)\n);\n\nvec2 rvertexcoord(int id) {\n    return RECT_VERTICES_COORDS[id % 4];\n}\n"),
    SHADER_4("texture.fsh", "#version 150\n\nin vec2 FragCoord; // normalized fragment coord relative to the primitive\nin vec2 TexCoord;\nin vec4 FragColor;\n\nuniform sampler2D Sampler0;\nuniform vec2 Size; // rectangle size\nuniform vec4 Radius; // radius for each vertex\nuniform float Smoothness; // edge smoothness;\n\nout vec4 OutColor;\n\n#import <common.glsl>\n\nvoid main() {\n    float alpha = ralpha(Size, FragCoord, Radius, Smoothness);\n    vec4 color = vec4(1.0, 1.0, 1.0, alpha) * texture(Sampler0, TexCoord) * FragColor;\n\n    if (color.a == 0.0) { // alpha test\n        discard;\n    }\n\n    OutColor = color;\n}\n"),
    SHADER_5("texture.vsh", "#version 150\n\nin vec3 Position; // POSITION_TEXTURE_COLOR vertex attributes\nin vec2 UV0;\nin vec4 Color;\n\nuniform mat4 ModelViewMat;\nuniform mat4 ProjMat;\n\nout vec2 FragCoord;\nout vec2 TexCoord;\nout vec4 FragColor;\n\n#import <common.glsl>\n\nvoid main() {\n    FragCoord = rvertexcoord(gl_VertexID);\n    TexCoord = UV0;\n    FragColor = Color;\n\n    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n}\n"),
    SHADER_6("bloom.fsh", "#version 150\n\nin vec2 FragCoord; // normalized fragment coord relative to the primitive\nin vec2 TexCoord;\nin vec4 FragColor;\n\nuniform sampler2D Sampler0;\nuniform vec2 Size; // rectangle size\nuniform vec4 Radius; // radius for each vertex\nuniform float Smoothness; // edge smoothness\nuniform float BlurRadius;\nuniform float Opacity;\n\nout vec4 OutColor;\n\nconst float DPI = 6.28318530718;\nconst float STEP = DPI / 16.0;\n\n#import <common.glsl>\n\nvoid main() {\n    float factor = ralpha(Size, FragCoord, Radius, Smoothness);\n    if (factor == 0.0) {\n        discard;\n    }\n\n    vec2 multiplier = BlurRadius / textureSize(Sampler0, 0);\n    vec2 tex = TexCoord;\n\n    vec3 average = texture(Sampler0, tex).rgb;\n    for (float d = 0.0; d < DPI; d += STEP) {\n        for (float i = 0.2; i <= 1.0; i += 0.2) {\n            average += texture(Sampler0, tex + vec2(cos(d), sin(d)) * multiplier * i).rgb;\n        }\n    }\n    average /= 80.0;\n\n    vec4 color = vec4(average, Opacity) * FragColor;\n    color.a *= factor;\n\n    if (color.a == 0.0) { // alpha test\n        discard;\n    }\n\n    OutColor = color;\n}\n"),
    SHADER_7("blur.fsh", "#version 150\n\nin vec2 FragCoord; // normalized fragment coord relative to the primitive\nin vec2 TexCoord;\nin vec4 FragColor;\n\nuniform sampler2D Sampler0;\nuniform vec2 Size; // rectangle size\nuniform vec4 Radius; // radius for each vertex\nuniform float Smoothness; // edge smoothness\nuniform float BlurRadius;\nuniform float Opacity;\n\nout vec4 OutColor;\n\nconst float DPI = 6.28318530718;\nconst float STEP = DPI / 16.0;\n\n#import <common.glsl>\n\nvoid main() {\n    float factor = ralpha(Size, FragCoord, Radius, Smoothness);\n    if (factor == 0.0) {\n        discard;\n    }\n\n    vec2 multiplier = BlurRadius / textureSize(Sampler0, 0);\n    vec2 tex = TexCoord;\n\n    vec3 average = texture(Sampler0, tex).rgb;\n    for (float d = 0.0; d < DPI; d += STEP) {\n        for (float i = 0.2; i <= 1.0; i += 0.2) {\n            average += texture(Sampler0, tex + vec2(cos(d), sin(d)) * multiplier * i).rgb;\n        }\n    }\n    average /= 80.0;\n\n    vec4 color = vec4(average, Opacity) * FragColor;\n    color.a *= factor;\n\n    if (color.a == 0.0) { // alpha test\n        discard;\n    }\n\n    OutColor = color;\n}\n"),
    SHADER_8("blur.vsh", "#version 150\n\nin vec3 Position; // POSITION_COLOR vertex attributes\nin vec2 UV0;\nin vec4 Color;\n\nuniform mat4 ModelViewMat;\nuniform mat4 ProjMat;\n\nout vec2 FragCoord;\nout vec2 TexCoord;\nout vec4 FragColor;\n\n#import <common.glsl>\n\nvoid main() {\n    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n\n    FragCoord = rvertexcoord(gl_VertexID);\n    TexCoord = gl_Position.xy * 0.5 + 0.5;\n    FragColor = Color;\n}\n"),
    SHADER_9("rounded_rect.fsh", "#version 150\n\nin vec2 TexCoord;\nin vec4 FragColor;\n\nuniform vec2 Size;\nuniform float Radius;\nuniform float Smoothness;\n\nout vec4 OutColor;\n\nfloat roundSDF(vec2 p, vec2 b, float r) {\n\treturn length(max(abs(p) - b, 0.0)) - r;\n}\n\nvoid main() {\n\tvec2 halfSize = Size * .5;\n\tfloat smoothedAlpha = (1.0 - smoothstep(1.0 - Smoothness, 1.0, roundSDF(halfSize - (TexCoord * Size), halfSize - Radius - Smoothness * 0.5f, Radius))) * FragColor.a;\n\n    if (smoothedAlpha == 0.0) discard;\n\n\tOutColor = vec4(FragColor.rgb, smoothedAlpha);\n}\n"),
    SHADER_10("stencil.fsh", "#version 150\n\nin vec2 TexCoord;\n\nuniform sampler2D StencilTexture, TargetTexture;\nuniform float Opacity;\n\nout vec4 OutColor;\n\nvoid main() {\n    float stencilAlpha = texture2D(StencilTexture, TexCoord).a;\n\n    if (stencilAlpha == 0.0) discard;\n\n    vec4 targetColor = texture(TargetTexture, TexCoord);\n    OutColor = vec4(targetColor.rgb, targetColor.a * stencilAlpha * Opacity);\n}\n");

    @Getter
    private final String fileName;
    @Getter
    private final String source;
    private static final HashMap<String, ShaderSource> BY_FILENAME;

    public static ShaderSource getByFileName(String fileName) {
        return BY_FILENAME.getOrDefault(fileName, SHADER_0);
    }

    @Generated
    ShaderSource(String fileName, String source) {
        this.fileName = fileName;
        this.source = source;
    }

    static {
        BY_FILENAME = new HashMap<>();
        for (ShaderSource shaderSource : ShaderSource.values()) {
            BY_FILENAME.put(shaderSource.getFileName(), shaderSource);
        }
    }
}