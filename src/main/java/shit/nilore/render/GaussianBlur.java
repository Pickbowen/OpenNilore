package shit.nilore.render;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Kernel;

public class GaussianBlur {
    protected float radius;
    protected Kernel kernel;
    private static final String NAME = "Blur/Gaussian Blur...";

    public GaussianBlur(float radius) {
        this.setRadius(radius);
    }

    public static void convolve(Kernel kernel, int[] src, int[] dst, int width, int height, boolean alphaChannel, boolean premultiply, boolean unpremultiply, int edgeAction) {
        float[] kernelData = kernel.getKernelData(null);
        int kernelWidth = kernel.getWidth();
        int half = kernelWidth / 2;
        for (int i = 0; i < height; ++i) {
            int dstIndex = i;
            int srcRowStart = i * width;
            for (int j = 0; j < width; ++j) {
                int argb;
                int srcX;
                int k;
                float red = 0.0f;
                float green = 0.0f;
                float blue = 0.0f;
                float alpha = 0.0f;
                int kernelOffset = half;
                for (k = -half; k <= half; ++k) {
                    float weight = kernelData[kernelOffset + k];
                    if (weight == 0.0f) continue;
                    srcX = j + k;
                    if (srcX < 0) {
                        if (edgeAction == 1) {
                            srcX = 0;
                        } else if (edgeAction == 2) {
                            srcX = (j + width) % width;
                        }
                    } else if (srcX >= width) {
                        if (edgeAction == 1) {
                            srcX = width - 1;
                        } else if (edgeAction == 2) {
                            srcX = (j + width) % width;
                        }
                    }
                    argb = src[srcRowStart + srcX];
                    int a = argb >> 24 & 0xFF;
                    int r = argb >> 16 & 0xFF;
                    int g = argb >> 8 & 0xFF;
                    int b = argb & 0xFF;
                    if (premultiply) {
                        float scale = (float)a * 0.003921569f;
                        r = (int)((float)r * scale);
                        g = (int)((float)g * scale);
                        b = (int)((float)b * scale);
                    }
                    alpha += weight * (float)a;
                    red += weight * (float)r;
                    green += weight * (float)g;
                    blue += weight * (float)b;
                }
                if (unpremultiply && alpha != 0.0f && alpha != 255.0f) {
                    float invAlpha = 255.0f / alpha;
                    red *= invAlpha;
                    green *= invAlpha;
                    blue *= invAlpha;
                }
                k = alphaChannel ? GaussianBlur.clamp((int)((double)alpha + 0.5)) : 255;
                int outR = GaussianBlur.clamp((int)((double)red + 0.5));
                srcX = GaussianBlur.clamp((int)((double)green + 0.5));
                argb = GaussianBlur.clamp((int)((double)blue + 0.5));
                dst[dstIndex] = k << 24 | outR << 16 | srcX << 8 | argb;
                dstIndex += height;
            }
        }
    }

    public static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    public static Kernel makeKernel(float radius) {
        int i;
        int kernelHalf = (int)Math.ceil(radius);
        int kernelSize = kernelHalf * 2 + 1;
        float[] kernelData = new float[kernelSize];
        float sigma = radius / 3.0f;
        float twoSigmaSq = 2.0f * sigma * sigma;
        float sigmaSqTwoPi = (float)Math.PI * 2 * sigma;
        float sqrtTwoPiSigma = (float)Math.sqrt(sigmaSqTwoPi);
        float radiusSq = radius * radius;
        float sum = 0.0f;
        int index = 0;
        for (i = -kernelHalf; i <= kernelHalf; ++i) {
            float distSq = i * i;
            kernelData[index] = distSq > radiusSq ? 0.0f : (float)Math.exp(-distSq / twoSigmaSq) / sqrtTwoPiSigma;
            sum += kernelData[index];
            ++index;
        }
        for (i = 0; i < kernelSize; ++i) {
            kernelData[i] = kernelData[i] / sum;
        }
        return new Kernel(kernelSize, 1, kernelData);
    }

    public void setRadius(float radius) {
        this.radius = radius;
        this.kernel = GaussianBlur.makeKernel(radius);
    }

    public BufferedImage filter(BufferedImage source, BufferedImage dest) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (dest == null) {
            dest = this.createCompatibleDestImage(source, null);
        }
        int[] srcPixels = new int[width * height];
        int[] dstPixels = new int[width * height];
        source.getRGB(0, 0, width, height, srcPixels, 0, width);
        if (this.radius > 0.0f) {
            GaussianBlur.convolve(this.kernel, srcPixels, dstPixels, width, height, true, true, false, 1);
            GaussianBlur.convolve(this.kernel, dstPixels, srcPixels, height, width, true, false, true, 1);
        }
        dest.setRGB(0, 0, width, height, srcPixels, 0, width);
        return dest;
    }

    public BufferedImage createCompatibleDestImage(BufferedImage source, ColorModel colorModel) {
        if (colorModel == null) {
            colorModel = source.getColorModel();
        }
        return new BufferedImage(colorModel, colorModel.createCompatibleWritableRaster(source.getWidth(), source.getHeight()), colorModel.isAlphaPremultiplied(), null);
    }

    public String toString() {
        return NAME;
    }
}