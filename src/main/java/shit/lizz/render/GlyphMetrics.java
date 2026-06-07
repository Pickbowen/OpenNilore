package shit.lizz.render;

public record GlyphMetrics(float ascent, float descent, float height, float capHeight) {

    public float getLineGap() {
        return this.height - (this.descent - this.ascent);
    }

    public float getLineHeight() {
        return this.descent - this.ascent;
    }
}