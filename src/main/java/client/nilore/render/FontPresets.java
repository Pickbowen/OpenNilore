package client.nilore.render;

import client.nilore.render.FontRenderer;
import client.nilore.render.Fonts;

public final class FontPresets {
    public static FontRenderer pingfang(float size) {
        return Fonts.getRenderer("pingfang_sc_regular.ttf", size);
    }

    /**
     * 苹方合成粗体。
     *
     * <p>苹方只带了 Regular 一份，没有 Bold 变体，所以粗体是在字形光栅化阶段靠重复描边合成的。
     * 歌词、标题这类需要「压得住画面」的文本用它，常规正文还是走 {@link #pingfang}。
     */
    public static FontRenderer pingfangBold(float size) {
        return new FontRenderer("pingfang_sc_regular.ttf", size, true);
    }

    public static FontRenderer productSans(float size) {
        return Fonts.getRenderer("product_sans_regular.ttf", size);
    }

    /**
     * Product Sans（Google Sans 的近亲），西文和数字用它，中文会自动落到苹方。
     *
     * <p>沉浸式播放页走这一档：标题、时间戳、歌词都用它，比全苹方更接近 Apple Music 的观感。
     */
    public static FontRenderer googleSans(float size) {
        return Fonts.getRenderer("product_sans_regular.ttf", size);
    }

    /** Product Sans 合成粗体（字体只有 Regular 一份，粗体靠描边合成）。 */
    public static FontRenderer googleSansBold(float size) {
        return new FontRenderer("product_sans_regular.ttf", size, true);
    }

    public static FontRenderer astaSans(float size) {
        return Fonts.getRenderer("AstaSans-Medium.ttf", size);
    }

    public static FontRenderer poppinsRegular(float size) {
        return Fonts.getRenderer("Poppins-Regular.ttf", size);
    }

    public static FontRenderer poppinsMedium(float size) {
        return Fonts.getRenderer("Poppins-Medium.ttf", size);
    }

    public static FontRenderer poppinsBold(float size) {
        return Fonts.getRenderer("Poppins-Bold.ttf", size);
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