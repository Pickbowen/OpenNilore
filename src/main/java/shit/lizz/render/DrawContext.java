package shit.lizz.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public class DrawContext {

    public static final class StrokeState {
        private final Paint.LinearGradient gradient;
        private float dashOffset;
        private boolean inDash;

        StrokeState(Paint.LinearGradient gradient) {
            this.gradient = gradient;
            this.inDash = true;
            this.dashOffset = 0.0f;
            if (gradient != null) {
                this.dashOffset = gradient.angle;
            }
        }

        boolean isDrawing() {
            return this.gradient == null || this.inDash;
        }

        void advance(float length) {
            if (this.gradient == null || this.gradient.colors == null || this.gradient.colors.length == 0) {
                return;
            }
            this.dashOffset += length;
            while (this.dashOffset >= this.currentDashLength()) {
                this.dashOffset -= this.currentDashLength();
                this.inDash = !this.inDash;
            }
        }

        private float currentDashLength() {
            int index = this.inDash ? 0 : (this.gradient.colors.length > 1 ? 1 : 0);
            return Math.max(0.001f, this.gradient.colors[index]);
        }
    }

    private static final RoundedRectShader ROUNDED_RECT_SHADER = new RoundedRectShader();
    private final PoseStack poseStack;
    private final GuiGraphics guiGraphics;
    private final Deque<Boolean> clipStack = new ArrayDeque<>();

    public static RoundedRectShader getRoundedRectShader() {
        return ROUNDED_RECT_SHADER;
    }

    public DrawContext(GuiGraphics guiGraphics) {
        this.guiGraphics = guiGraphics;
        this.poseStack = new PoseStack();
    }

    public DrawContext(GuiGraphics guiGraphics, PoseStack poseStack) {
        this.guiGraphics = guiGraphics;
        this.poseStack = poseStack != null ? poseStack : new PoseStack();
    }

    public PoseStack getPoseStack() {
        return this.poseStack;
    }

    public GuiGraphics getGuiGraphics() {
        return this.guiGraphics;
    }

    public void save() {
        this.poseStack.pushPose();
        this.clipStack.push(Boolean.FALSE);
    }

    public void restore() {
        boolean useScissor;
        if (!this.clipStack.isEmpty() && (useScissor = this.clipStack.pop()) && this.guiGraphics != null) {
            this.guiGraphics.disableScissor();
        }
        this.poseStack.popPose();
    }

    public void translate(float x, float y) {
        this.poseStack.translate(x, y, 0.0f);
    }

    public void scale(float scaleX, float scaleY) {
        this.poseStack.scale(scaleX, scaleY, 1.0f);
    }

    public void rotate(float degrees) {
        this.poseStack.mulPose(Axis.ZP.rotationDegrees(degrees));
    }

    public void flush() {
    }

    public void clip(Rectangle rectangle) {
        this.clipRect(rectangle, true);
    }

    public void clipRect(Rectangle rectangle, boolean enable) {
        if (this.guiGraphics == null) {
            return;
        }
        Matrix4f pose = this.poseStack.last().pose();
        Vector4f topLeft = new Vector4f(rectangle.getX(), rectangle.getY(), 0.0f, 1.0f).mul(pose);
        Vector4f bottomRight = new Vector4f(rectangle.getRight(), rectangle.getBottom(), 0.0f, 1.0f).mul(pose);
        int minX = (int)Math.floor(Math.min(topLeft.x, bottomRight.x));
        int minY = (int)Math.floor(Math.min(topLeft.y, bottomRight.y));
        int maxX = (int)Math.ceil(Math.max(topLeft.x, bottomRight.x));
        int maxY = (int)Math.ceil(Math.max(topLeft.y, bottomRight.y));
        this.guiGraphics.enableScissor(minX, minY, maxX, maxY);
        this.updateClipStack();
    }

    public void clipRoundedRect(RoundedRectangle roundedRectangle, boolean enable) {
        if (this.guiGraphics == null) {
            return;
        }
        Matrix4f pose = this.poseStack.last().pose();
        Vector4f topLeft = new Vector4f(roundedRectangle.x1, roundedRectangle.y1, 0.0f, 1.0f).mul(pose);
        Vector4f bottomRight = new Vector4f(roundedRectangle.x2, roundedRectangle.y2, 0.0f, 1.0f).mul(pose);
        int minX = (int)Math.floor(Math.min(topLeft.x, bottomRight.x));
        int minY = (int)Math.floor(Math.min(topLeft.y, bottomRight.y));
        int maxX = (int)Math.ceil(Math.max(topLeft.x, bottomRight.x));
        int maxY = (int)Math.ceil(Math.max(topLeft.y, bottomRight.y));
        this.guiGraphics.enableScissor(minX, minY, maxX, maxY);
        this.updateClipStack();
    }

    private void updateClipStack() {
        if (!this.clipStack.isEmpty()) {
            this.clipStack.pop();
            this.clipStack.push(Boolean.TRUE);
        }
    }

    public void drawRect(Rectangle rectangle, Paint paint) {
        this.drawRectXYWH(rectangle.getX(), rectangle.getY(), rectangle.getWidth(), rectangle.getHeight(), paint);
    }

    public void drawRectXYWH(float x, float y, float width, float height, Paint paint) {
        if (paint.getCapStyle() == Paint.StrokeCap.STROKE) {
            this.drawRectStroke(x, y, width, height, paint);
            return;
        }
        this.setupColorShader();
        Matrix4f pose = this.poseStack.last().pose();
        float[] color = DrawContext.colorToFloats(paint.getColor());
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (paint.getGradCoords() != null) {
            boolean vertical;
            Paint.GradientCoords gradCoords = paint.getGradCoords();
            float[] color1 = DrawContext.colorToFloats(gradCoords.color1);
            float[] color2 = DrawContext.colorToFloats(gradCoords.color2);
            boolean isVertical = vertical = Math.abs(gradCoords.y2 - gradCoords.y1) >= Math.abs(gradCoords.x2 - gradCoords.x1);
            if (vertical) {
                bufferBuilder.vertex(pose, x, y, 0.0f).color(color1[0], color1[1], color1[2], color1[3]).endVertex();
                bufferBuilder.vertex(pose, x, y + height, 0.0f).color(color2[0], color2[1], color2[2], color2[3]).endVertex();
                bufferBuilder.vertex(pose, x + width, y + height, 0.0f).color(color2[0], color2[1], color2[2], color2[3]).endVertex();
                bufferBuilder.vertex(pose, x + width, y, 0.0f).color(color1[0], color1[1], color1[2], color1[3]).endVertex();
            } else {
                bufferBuilder.vertex(pose, x, y, 0.0f).color(color1[0], color1[1], color1[2], color1[3]).endVertex();
                bufferBuilder.vertex(pose, x, y + height, 0.0f).color(color1[0], color1[1], color1[2], color1[3]).endVertex();
                bufferBuilder.vertex(pose, x + width, y + height, 0.0f).color(color2[0], color2[1], color2[2], color2[3]).endVertex();
                bufferBuilder.vertex(pose, x + width, y, 0.0f).color(color2[0], color2[1], color2[2], color2[3]).endVertex();
            }
        } else {
            bufferBuilder.vertex(pose, x, y, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
            bufferBuilder.vertex(pose, x, y + height, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
            bufferBuilder.vertex(pose, x + width, y + height, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
            bufferBuilder.vertex(pose, x + width, y, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        }
        tesselator.end();
    }

    public void drawRoundedRect(RoundedRectangle roundedRectangle, Paint paint) {
        int color2;
        int color1 = color2 = paint.getColor();
        boolean hasGradient = false;
        Paint.GradientCoords gradCoords = paint.getGradCoords();
        if (gradCoords != null) {
            color1 = gradCoords.color1;
            color2 = gradCoords.color2;
            hasGradient = true;
        }
        float strokeWidth = paint.getCapStyle() == Paint.StrokeCap.STROKE ? Math.max(0.5f, paint.getStrokeWidth()) : 0.0f;
        ROUNDED_RECT_SHADER.draw(this.poseStack.last().pose(), roundedRectangle.x1, roundedRectangle.y1, roundedRectangle.x2, roundedRectangle.y2, roundedRectangle.topLeftRadius, roundedRectangle.topRightRadius, roundedRectangle.bottomRightRadius, roundedRectangle.bottomLeftRadius, color1, color2, hasGradient, strokeWidth);
    }

    private void drawCornerArc(BufferBuilder bufferBuilder, Matrix4f pose, float centerX, float centerY, float radius, float startAngle, float sweepAngle, int segments, Paint paint, RoundedRectangle roundedRectangle) {
        int color = paint.getColor();
        Paint.GradientCoords gradCoords = paint.getGradCoords();
        if (radius <= 0.0f) {
            float[] colorFloats = DrawContext.interpolateGradientColor(gradCoords, centerX, centerY, roundedRectangle, color);
            bufferBuilder.vertex(pose, centerX, centerY, 0.0f).color(colorFloats[0], colorFloats[1], colorFloats[2], colorFloats[3]).endVertex();
            return;
        }
        for (int i = 0; i <= segments; ++i) {
            float t = (float)i / (float)segments;
            double angle = Math.toRadians(startAngle + (sweepAngle - startAngle) * t);
            float vx = centerX + (float)Math.cos(angle) * radius;
            float vy = centerY + (float)Math.sin(angle) * radius;
            float[] colorFloats = DrawContext.interpolateGradientColor(gradCoords, vx, vy, roundedRectangle, color);
            bufferBuilder.vertex(pose, vx, vy, 0.0f).color(colorFloats[0], colorFloats[1], colorFloats[2], colorFloats[3]).endVertex();
        }
    }

    private static float[] interpolateGradientColor(Paint.GradientCoords gradCoords, float vx, float vy, RoundedRectangle roundedRectangle, int fallbackColor) {
        if (gradCoords == null) {
            return DrawContext.colorToFloats(fallbackColor);
        }
        float height = roundedRectangle.getHeight();
        if (height <= 0.0f) {
            return DrawContext.colorToFloats(gradCoords.color1);
        }
        float t = Math.max(0.0f, Math.min(1.0f, (vy - roundedRectangle.y1) / height));
        float[] color1 = DrawContext.colorToFloats(gradCoords.color1);
        float[] color2 = DrawContext.colorToFloats(gradCoords.color2);
        return new float[]{color1[0] + (color2[0] - color1[0]) * t, color1[1] + (color2[1] - color1[1]) * t, color1[2] + (color2[2] - color1[2]) * t, color1[3] + (color2[3] - color1[3]) * t};
    }

    private void drawRectStroke(float x, float y, float width, float height, Paint paint) {
        float strokeWidth = Math.max(0.5f, paint.getStrokeWidth());
        float half = strokeWidth * 0.5f;
        this.drawRectXYWH(x - half, y - half, width + strokeWidth, strokeWidth, paint.copy());
        this.drawRectXYWH(x - half, y + height - half, width + strokeWidth, strokeWidth, paint.copy());
        this.drawRectXYWH(x - half, y - half, strokeWidth, height + strokeWidth, paint.copy());
        this.drawRectXYWH(x + width - half, y - half, strokeWidth, height + strokeWidth, paint.copy());
    }

    private void drawRoundedRectStroke(RoundedRectangle roundedRectangle, Paint paint) {
        Paint copy = paint.copy();
        this.drawRectStroke(roundedRectangle.x1, roundedRectangle.y1, roundedRectangle.getWidth(), roundedRectangle.getHeight(), paint);
    }

    public void drawLine(float x1, float y1, float x2, float y2, Paint paint) {
        float strokeWidth = Math.max(0.5f, paint.getStrokeWidth());
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float)Math.hypot(dx, dy);
        if (length < 1.0E-4f) {
            return;
        }
        float normalX = -dy / length * strokeWidth * 0.5f;
        float normalY = dx / length * strokeWidth * 0.5f;
        this.setupColorShader();
        Matrix4f pose = this.poseStack.last().pose();
        float[] color = DrawContext.colorToFloats(paint.getColor());
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(pose, x1 + normalX, y1 + normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, x1 - normalX, y1 - normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, x2 - normalX, y2 - normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, x2 + normalX, y2 + normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        tesselator.end();
    }

    public void drawString(String text, float x, float y, FontRenderer fontRenderer, Paint paint) {
        if (text == null || text.isEmpty()) {
            return;
        }
        CustomFont customFont = fontRenderer.getFont();
        if (customFont == null) {
            return;
        }
        GlyphMetrics glyphMetrics = fontRenderer.getMetrics();
        float baselineY = y + glyphMetrics.ascent();
        customFont.drawString(this.poseStack, text, x, baselineY, paint.getColor());
    }

    public void drawArc(float x1, float y1, float x2, float y2, float startAngle, float sweepAngle, boolean unused, Paint paint) {
        this.setupColorShader();
        Matrix4f pose = this.poseStack.last().pose();
        float centerX = (x1 + x2) * 0.5f;
        float centerY = (y1 + y2) * 0.5f;
        float radius = Math.min(x2 - x1, y2 - y1) * 0.5f;
        float[] color = DrawContext.colorToFloats(paint.getColor());
        int segments = 32;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        if (paint.getCapStyle() == Paint.StrokeCap.STROKE) {
            float strokeWidth = Math.max(0.5f, paint.getStrokeWidth());
            float innerRadius = radius - strokeWidth * 0.5f;
            float outerRadius = radius + strokeWidth * 0.5f;
            bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i <= segments; ++i) {
                float t = (float)i / (float)segments;
                double angle = Math.toRadians(startAngle + sweepAngle * t);
                float cos = (float)Math.cos(angle);
                float sin = (float)Math.sin(angle);
                bufferBuilder.vertex(pose, centerX + cos * outerRadius, centerY + sin * outerRadius, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
                bufferBuilder.vertex(pose, centerX + cos * innerRadius, centerY + sin * innerRadius, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
            }
        } else {
            bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            bufferBuilder.vertex(pose, centerX, centerY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
            for (int i = 0; i <= segments; ++i) {
                float t = (float)i / (float)segments;
                double angle = Math.toRadians(startAngle + sweepAngle * t);
                float vx = centerX + (float)Math.cos(angle) * radius;
                float vy = centerY + (float)Math.sin(angle) * radius;
                bufferBuilder.vertex(pose, vx, vy, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
            }
        }
        tesselator.end();
    }

    public void drawPath(Path path, Paint paint) {
        if (path == null) {
            return;
        }
        float strokeWidth = Math.max(0.5f, paint.getStrokeWidth());
        float[] color = DrawContext.colorToFloats(paint.getColor());
        DrawContext.StrokeState strokeState = new DrawContext.StrokeState(paint.getLinGradient());
        Matrix4f pose = this.poseStack.last().pose();
        float currX = 0.0f;
        float currY = 0.0f;
        float startX = 0.0f;
        float startY = 0.0f;
        boolean hasCurrent = false;
        if (paint.getCapStyle() == Paint.StrokeCap.STROKE || paint.getCapStyle() == Paint.StrokeCap.STROKE_AND_FILL) {
            for (Path.PathSegment path$PathSegment : path.getSegments()) {
                switch (path$PathSegment.type) {
                    case MOVE_TO: {
                        currX = path$PathSegment.coords[0];
                        currY = path$PathSegment.coords[1];
                        startX = currX;
                        startY = currY;
                        hasCurrent = true;
                        break;
                    }
                    case LINE_TO: {
                        if (hasCurrent) {
                            this.drawStrokedLineSegment(pose, currX, currY, path$PathSegment.coords[0], path$PathSegment.coords[1], strokeWidth, color, strokeState);
                        }
                        currX = path$PathSegment.coords[0];
                        currY = path$PathSegment.coords[1];
                        hasCurrent = true;
                        break;
                    }
                    case QUAD_TO: {
                        if (hasCurrent) {
                            this.drawStrokedCubicBezier(pose, currX, currY, (currX + 2.0f * path$PathSegment.coords[0]) / 3.0f, (currY + 2.0f * path$PathSegment.coords[1]) / 3.0f, (path$PathSegment.coords[2] + 2.0f * path$PathSegment.coords[0]) / 3.0f, (path$PathSegment.coords[3] + 2.0f * path$PathSegment.coords[1]) / 3.0f, path$PathSegment.coords[2], path$PathSegment.coords[3], strokeWidth, color, strokeState);
                        }
                        currX = path$PathSegment.coords[2];
                        currY = path$PathSegment.coords[3];
                        break;
                    }
                    case CUBIC_TO: {
                        if (hasCurrent) {
                            this.drawStrokedCubicBezier(pose, currX, currY, path$PathSegment.coords[0], path$PathSegment.coords[1], path$PathSegment.coords[2], path$PathSegment.coords[3], path$PathSegment.coords[4], path$PathSegment.coords[5], strokeWidth, color, strokeState);
                        }
                        currX = path$PathSegment.coords[4];
                        currY = path$PathSegment.coords[5];
                        break;
                    }
                    case CLOSE: {
                        if (hasCurrent) {
                            this.drawStrokedLineSegment(pose, currX, currY, startX, startY, strokeWidth, color, strokeState);
                        }
                        currX = startX;
                        currY = startY;
                        break;
                    }
                    case RRECT: {
                        this.drawRoundedRectStroke(path$PathSegment.roundedRect, paint);
                        break;
                    }
                    case RECT: {
                        this.drawRectStroke(path$PathSegment.rect.getX(), path$PathSegment.rect.getY(), path$PathSegment.rect.getWidth(), path$PathSegment.rect.getHeight(), paint);
                    }
                }
            }
        }
        if (paint.getCapStyle() == Paint.StrokeCap.FILL || paint.getCapStyle() == Paint.StrokeCap.STROKE_AND_FILL) {
            this.fillPath(path, paint);
        }
    }

    private void fillPath(Path path, Paint paint) {
        ArrayList<float[]> polygon = new ArrayList<>();
        float currX = 0.0f;
        float currY = 0.0f;
        float startX = 0.0f;
        float startY = 0.0f;
        block7: for (Path.PathSegment path$PathSegment : path.getSegments()) {
            switch (path$PathSegment.type) {
                case MOVE_TO: {
                    if (!polygon.isEmpty()) {
                        this.fillPolygon((List<float[]>)polygon, paint);
                        polygon.clear();
                    }
                    currX = path$PathSegment.coords[0];
                    currY = path$PathSegment.coords[1];
                    startX = currX;
                    startY = currY;
                    polygon.add(new float[]{currX, currY});
                    continue block7;
                }
                case LINE_TO: {
                    currX = path$PathSegment.coords[0];
                    currY = path$PathSegment.coords[1];
                    polygon.add(new float[]{currX, currY});
                    continue block7;
                }
                case CLOSE: {
                    if (!polygon.isEmpty()) {
                        this.fillPolygon((List<float[]>)polygon, paint);
                        polygon.clear();
                    }
                    currX = startX;
                    currY = startY;
                    continue block7;
                }
                case RRECT: {
                    this.drawRoundedRect(path$PathSegment.roundedRect, paint);
                    continue block7;
                }
                case RECT: {
                    this.drawRect(path$PathSegment.rect, paint);
                    continue block7;
                }
            }
        }
        if (!polygon.isEmpty()) {
            this.fillPolygon((List<float[]>)polygon, paint);
        }
    }

    private void fillPolygon(List<float[]> polygon, Paint paint) {
        if (polygon.size() < 3) {
            return;
        }
        this.setupColorShader();
        Matrix4f pose = this.poseStack.last().pose();
        float[] color = DrawContext.colorToFloats(paint.getColor());
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        for (float[] vertex : polygon) {
            bufferBuilder.vertex(pose, vertex[0], vertex[1], 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        }
        tesselator.end();
    }

    private void drawStrokedCubicBezier(Matrix4f pose, float p0x, float p0y, float p1x, float p1y, float p2x, float p2y, float p3x, float p3y, float strokeWidth, float[] color, DrawContext.StrokeState strokeState) {
        int steps = 24;
        float prevX = p0x;
        float prevY = p0y;
        for (int i = 1; i <= steps; ++i) {
            float t = (float)i / (float)steps;
            float oneMinusT = 1.0f - t;
            float x = oneMinusT * oneMinusT * oneMinusT * p0x + 3.0f * oneMinusT * oneMinusT * t * p1x + 3.0f * oneMinusT * t * t * p2x + t * t * t * p3x;
            float y = oneMinusT * oneMinusT * oneMinusT * p0y + 3.0f * oneMinusT * oneMinusT * t * p1y + 3.0f * oneMinusT * t * t * p2y + t * t * t * p3y;
            this.drawStrokedLineSegment(pose, prevX, prevY, x, y, strokeWidth, color, strokeState);
            prevX = x;
            prevY = y;
        }
    }

    private void drawStrokedLineSegment(Matrix4f pose, float x1, float y1, float x2, float y2, float strokeWidth, float[] color, DrawContext.StrokeState strokeState) {
        if (strokeState.isDrawing()) {
            this.drawLineSegment(pose, x1, y1, x2, y2, strokeWidth, color);
        }
        strokeState.advance((float)Math.hypot(x2 - x1, y2 - y1));
    }

    private void drawLineSegment(Matrix4f pose, float x1, float y1, float x2, float y2, float strokeWidth, float[] color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float)Math.hypot(dx, dy);
        if (length < 1.0E-4f) {
            return;
        }
        float normalX = -dy / length * strokeWidth * 0.5f;
        float normalY = dx / length * strokeWidth * 0.5f;
        this.setupColorShader();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(pose, x1 + normalX, y1 + normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, x1 - normalX, y1 - normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, x2 - normalX, y2 - normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, x2 + normalX, y2 + normalY, 0.0f).color(color[0], color[1], color[2], color[3]).endVertex();
        tesselator.end();
    }

    public void drawTexture(Texture texture, Rectangle srcRect, Rectangle dstRect, Paint paint) {
        if (texture == null) {
            return;
        }
        this.setupTexShader();
        if (texture.getResourceLocation() != null) {
            RenderSystem.setShaderTexture(0, texture.getResourceLocation());
        } else {
            RenderSystem.setShaderTexture(0, texture.getGlId());
        }
        Matrix4f pose = this.poseStack.last().pose();
        float[] color = DrawContext.colorToFloats(paint.getColor());
        float u1 = srcRect.getX() / (float)texture.getWidth();
        float v1 = srcRect.getY() / (float)texture.getHeight();
        float u2 = srcRect.getRight() / (float)texture.getWidth();
        float v2 = srcRect.getBottom() / (float)texture.getHeight();
        float dstX = dstRect.getX();
        float dstY = dstRect.getY();
        float dstWidth = dstRect.getWidth();
        float dstHeight = dstRect.getHeight();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(pose, dstX, dstY, 0.0f).uv(u1, v1).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, dstX, dstY + dstHeight, 0.0f).uv(u1, v2).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, dstX + dstWidth, dstY + dstHeight, 0.0f).uv(u2, v2).color(color[0], color[1], color[2], color[3]).endVertex();
        bufferBuilder.vertex(pose, dstX + dstWidth, dstY, 0.0f).uv(u2, v1).color(color[0], color[1], color[2], color[3]).endVertex();
        tesselator.end();
    }

    public void drawBlurredRoundedRect(RoundedRectangle roundedRectangle, float offsetX, float offsetY, float blurRadius, float spread, int color) {
        float x = roundedRectangle.x1 + offsetX - spread;
        float y = roundedRectangle.y1 + offsetY - spread;
        float width = roundedRectangle.getWidth() + spread * 2.0f;
        float height = roundedRectangle.getHeight() + spread * 2.0f;
        float tlRadius = Math.max(0.0f, roundedRectangle.topLeftRadius + spread);
        float trRadius = Math.max(0.0f, roundedRectangle.topRightRadius + spread);
        float brRadius = Math.max(0.0f, roundedRectangle.bottomRightRadius + spread);
        float blRadius = Math.max(0.0f, roundedRectangle.bottomLeftRadius + spread);
        RoundedRectangle expanded = RoundedRectangle.ofXYWHRadii(x, y, width, height, new float[]{tlRadius, trRadius, brRadius, blRadius});
        Paint paint = new Paint().setColor(color);
        float halfBlur = Math.max(0.01f, blurRadius * 0.5f);
        BlurRenderer.renderBlur(this, x, y, width, height, halfBlur, () -> this.drawRoundedRect(expanded, paint));
    }

    public void drawBlur(float x, float y, float width, float height, float radius, Runnable runnable) {
        BlurRenderer.renderBlur(this, x, y, width, height, radius, runnable);
    }

    void clearClipStack() {
        while (!this.clipStack.isEmpty()) {
            boolean useScissor = this.clipStack.pop();
            if (!useScissor || this.guiGraphics == null) continue;
            this.guiGraphics.disableScissor();
        }
    }

    private void setupColorShader() {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
    }

    private void setupTexShader() {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
    }

    static float[] colorToFloats(int color) {
        return new float[]{(float)(color >> 16 & 0xFF) / 255.0f, (float)(color >> 8 & 0xFF) / 255.0f, (float)(color & 0xFF) / 255.0f, (float)(color >>> 24 & 0xFF) / 255.0f};
    }

    public static AbstractTexture getTexture(ResourceLocation resourceLocation) {
        return Minecraft.getInstance().getTextureManager().getTexture(resourceLocation);
    }
}