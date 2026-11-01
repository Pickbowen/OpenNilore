package client.nilore.render;

import client.nilore.render.FontRenderer;
import client.nilore.render.Fonts;

public final class FontPresets {
    public static FontRenderer pingfang(float size) {
        return Fonts.getRenderer("pingfang_sc_regular.ttf", size);
    }

    public static FontRenderer pingfang(float size, float rasterScale, boolean hinted) {
        return Fonts.getRenderer("pingfang_sc_regular.ttf", size, rasterScale, hinted);
    }

    public static FontRenderer pingfangBold(float size) {
        return new FontRenderer("pingfang_sc_regular.ttf", size, true);
    }

    public static FontRenderer productSans(float size) {
        return Fonts.getRenderer("product_sans_regular.ttf", size);
    }

    public static FontRenderer googleSans(float size) {
        return Fonts.getRenderer("product_sans_regular.ttf", size);
    }

    public static FontRenderer googleSansBold(float size) {
        return new FontRenderer("product_sans_regular.ttf", size, true);
    }

    public static FontRenderer astaSans(float size) {
        return Fonts.getRenderer("AstaSans-Medium.ttf", size);
    }

    public static FontRenderer poppinsRegular(float size) {
        return Fonts.getRenderer("Poppins-Regular.ttf", size);
    }

    public static FontRenderer poppinsRegular(float size, float rasterScale) {
        return Fonts.getRenderer("Poppins-Regular.ttf", size, rasterScale, true);
    }

    public static FontRenderer poppinsMedium(float size) {
        return Fonts.getRenderer("Poppins-Medium.ttf", size);
    }

    public static FontRenderer poppinsMedium(float size, float rasterScale) {
        return Fonts.getRenderer("Poppins-Medium.ttf", size, rasterScale, true);
    }

    public static FontRenderer poppinsBold(float size) {
        return Fonts.getRenderer("Poppins-Bold.ttf", size);
    }

    public static FontRenderer poppinsBold(float size, float rasterScale) {
        return Fonts.getRenderer("Poppins-Bold.ttf", size, rasterScale, true);
    }

    public static FontRenderer niloreIcon(float size) {
        return Fonts.getRenderer("niloreicon-Regular.ttf", size);
    }

    public static FontRenderer museoSans(float size) {
        return Fonts.getRenderer("MuseoSansCyrl-900.ttf", size);
    }

    public static FontRenderer openSans(float size) {
        return Fonts.getRenderer("opensans.ttf", size);
    }

    public static FontRenderer materialIcons(float size) {
        return Fonts.getRenderer("MaterialIcons-Regular.ttf", size);
    }

    public static FontRenderer axiformaBold(float size) {
        return Fonts.getRenderer("axiforma_bold.ttf", size);
    }

    public static FontRenderer axiformaRegular(float size) {
        return Fonts.getRenderer("axiforma_regular.ttf", size);
    }

    public static FontRenderer axiformaExtraBold(float size) {
        return Fonts.getRenderer("axiforma_extrabold.ttf", size);
    }
}