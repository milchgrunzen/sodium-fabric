package net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.services.PlatformBlockAccess;
import net.caffeinemc.mods.sodium.client.services.PlatformTextureAccess;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;

public class ChunkVertexConsumer implements VertexConsumer {
    private static final int ATTRIBUTE_POSITION_BIT = 1 << 0;
    private static final int ATTRIBUTE_COLOR_BIT = 1 << 1;
    private static final int ATTRIBUTE_TEXTURE_BIT = 1 << 2;
    private static final int ATTRIBUTE_LIGHT_BIT = 1 << 3;
    private static final int ATTRIBUTE_NORMAL_BIT = 1 << 4;
    private static final int REQUIRED_ATTRIBUTES = (1 << 5) - 1;

    private final ChunkModelBuilder modelBuilder;
    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    private Material material;
    private boolean isColorFixed;
    private int fixedColor = 0xFFFFFFFF;
    private int vertexIndex;
    private int writtenAttributes;
    private TranslucentGeometryCollector collector;

    public ChunkVertexConsumer(ChunkModelBuilder modelBuilder) {
        this.modelBuilder = modelBuilder;
    }

    public void setData(Material material, TranslucentGeometryCollector collector) {
        this.material = material;
        this.collector = collector;
    }

    @Override
    public @NotNull VertexConsumer addVertex(float x, float y, float z) {
        ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
        vertex.x = (float) x;
        vertex.y = (float) y;
        vertex.z = (float) z;
        this.writtenAttributes |= ATTRIBUTE_POSITION_BIT;
        return potentiallyEndVertex();
    }

    // Writing color ignores alpha since alpha is used as a color multiplier by Sodium.
    @Override
    public @NotNull VertexConsumer setColor(int red, int green, int blue, int alpha) {
        if (this.isColorFixed) {
            throw new IllegalStateException();
        }

        ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
        vertex.color = ColorABGR.pack(red, green, blue, 0xFF);
        this.writtenAttributes |= ATTRIBUTE_COLOR_BIT;
        return potentiallyEndVertex();
    }

    @Override
    public @NotNull VertexConsumer setColor(float red, float green, float blue, float alpha) {
        if (this.isColorFixed) {
            throw new IllegalStateException();
        }

        ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
        vertex.color = ColorABGR.pack(red, green, blue, 1);
        this.writtenAttributes |= ATTRIBUTE_COLOR_BIT;
        return potentiallyEndVertex();
    }

    @Override
    public @NotNull VertexConsumer setColor(int argb) {
        if (this.isColorFixed) {
            throw new IllegalStateException();
        }

        ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
        vertex.color = ColorARGB.toABGR(argb, 0xFF);
        this.writtenAttributes |= ATTRIBUTE_COLOR_BIT;
        return potentiallyEndVertex();
    }

    @Override
    public @NotNull VertexConsumer setUv(float u, float v) {
        ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
        vertex.u = u;
        vertex.v = v;
        this.writtenAttributes |= ATTRIBUTE_TEXTURE_BIT;
        return potentiallyEndVertex();
    }

    // Overlay is ignored for chunk geometry.
    @Override
    public @NotNull VertexConsumer setUv1(int u, int v) {
        return potentiallyEndVertex();
    }

    @Override
    public @NotNull VertexConsumer setOverlay(int uv) {
        return potentiallyEndVertex();
    }

    @Override
    public @NotNull VertexConsumer setUv2(int u, int v) {
        ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
        vertex.light = ((v & 0xFFFF) << 16) | (u & 0xFFFF);
        this.writtenAttributes |= ATTRIBUTE_LIGHT_BIT;
        return potentiallyEndVertex();
    }

    @Override
    public @NotNull VertexConsumer setLight(int uv) {
        ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
        vertex.light = uv;
        this.writtenAttributes |= ATTRIBUTE_LIGHT_BIT;
        return potentiallyEndVertex();
    }

    @Override
    public @NotNull VertexConsumer setNormal(float x, float y, float z) {
        this.writtenAttributes |= ATTRIBUTE_NORMAL_BIT;
        return potentiallyEndVertex();
    }

    public VertexConsumer potentiallyEndVertex() {
        if (this.isColorFixed) {
            ChunkVertexEncoder.Vertex vertex = this.vertices[this.vertexIndex];
            vertex.color = this.fixedColor;
            this.writtenAttributes |= ATTRIBUTE_COLOR_BIT;
        }

        if (this.writtenAttributes != REQUIRED_ATTRIBUTES) {
            return this;
        }

        this.vertexIndex++;
        this.writtenAttributes = 0;

        if (this.vertexIndex == 4) {
            int normal = calculateNormal();

            ModelQuadFacing cullFace = ModelQuadFacing.fromPackedNormal(normal);

            if (material == DefaultMaterials.TRANSLUCENT && collector != null) {
                collector.appendQuad(normal, vertices, cullFace);
            }

            this.modelBuilder.getVertexBuffer(cullFace).push(this.vertices, this.material);

            float u = 0;
            float v = 0;

            for (ChunkVertexEncoder.Vertex vertex : this.vertices) {
                u += vertex.u;
                v += vertex.v;
            }

            TextureAtlasSprite sprite = PlatformTextureAccess.getInstance().findInBlockAtlas(u * 0.25f, v * 0.25f);

            if (sprite != null) {
                this.modelBuilder.addSprite(sprite);
            }

            this.vertexIndex = 0;
        }

        return this;
    }

    private int calculateNormal() {
        final float x0 = vertices[0].x;
        final float y0 = vertices[0].y;
        final float z0 = vertices[0].z;

        final float x1 = vertices[1].x;
        final float y1 = vertices[1].y;
        final float z1 = vertices[1].z;

        final float x2 = vertices[2].x;
        final float y2 = vertices[2].y;
        final float z2 = vertices[2].z;

        final float x3 = vertices[3].x;
        final float y3 = vertices[3].y;
        final float z3 = vertices[3].z;

        final float dx0 = x2 - x0;
        final float dy0 = y2 - y0;
        final float dz0 = z2 - z0;
        final float dx1 = x3 - x1;
        final float dy1 = y3 - y1;
        final float dz1 = z3 - z1;

        float normX = dy0 * dz1 - dz0 * dy1;
        float normY = dz0 * dx1 - dx0 * dz1;
        float normZ = dx0 * dy1 - dy0 * dx1;

        // normalize by length for the packed normal
        float length = (float) Math.sqrt(normX * normX + normY * normY + normZ * normZ);
        if (length != 0.0 && length != 1.0) {
            normX /= length;
            normY /= length;
            normZ /= length;
        }

        return NormI8.pack(normX, normY, normZ);
    }
}