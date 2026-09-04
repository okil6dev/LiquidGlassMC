package restudio.reglass.client.screen.widget.world;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelSummary;
import org.slf4j.Logger;
import restudio.reglass.client.api.ReGlassApi;
import restudio.reglass.client.api.WidgetStyle;
import restudio.reglass.client.screen.widget.ScrollableListWidget;
import restudio.reglass.client.screen.world.CustomWorldSelectScreen;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
public class WorldListEntryWidget extends ScrollableListWidget.Entry<WorldListEntryWidget> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation DEFAULT_ICON_ID = ResourceLocation.withDefaultNamespace("textures/misc/unknown_server.png");
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());

    private final Minecraft client;
    private final CustomWorldSelectScreen parent;
    private final LevelSummary summary;
    private final ResourceLocation iconId;
    private final WidgetStyle defaultStyle = new WidgetStyle().tint(0x000000, 0.1f);
    private final WidgetStyle hoveredStyle = new WidgetStyle().tint(0xFFFFFF, 0.1f);
    private final WidgetStyle selectedStyle = new WidgetStyle().tint(0xFFFFFF, 0.2f);

    private DynamicTexture iconTexture;

    public WorldListEntryWidget(CustomWorldSelectScreen parent, LevelSummary summary, int x, int y, int height) {
        super(x, y, parent.width - 150 - 40, height);
        this.parent = parent;
        this.summary = summary;
        this.client = Minecraft.getInstance();
        String safeName = summary.getLevelId().toLowerCase().replaceAll("[^a-z0-9/._-]", "_");
        this.iconId = ResourceLocation.fromNamespaceAndPath("reglass", "world-select/icon/" + safeName);

        loadIcon();
    }

    private void loadIcon() {
        File iconFile = summary.getIcon().toFile();
        if (Files.isRegularFile(iconFile.toPath())) {
            try (InputStream inputStream = Files.newInputStream(iconFile.toPath())) {
                NativeImage image = NativeImage.read(inputStream);
                if (this.iconTexture != null) {
                    this.iconTexture.close();
                }
        this.iconTexture = new DynamicTexture(image);
                this.client.getTextureManager().register(this.iconId, this.iconTexture);
            } catch (Exception e) {
                LOGGER.error("Failed to load world icon for {}", summary.getLevelId(), e);
                this.iconTexture = null;
            }
        }
    }

    @Override
    public void render(GuiGraphics context, int index, int x, int y, int width, int height, int mouseX, int mouseY, boolean hovered, float delta) {
        super.render(context, index, x, y, width, height, mouseX, mouseY, hovered, delta);

        boolean isSelected = this.parent.getList().getSelectedEntries().contains(this);

        WidgetStyle style = defaultStyle;
        if (isSelected) {
            style = selectedStyle;
        } else if (hovered) {
            style = hoveredStyle;
        }

        ReGlassApi.create(context)
                .dimensions(x, y, width, height)
                .cornerRadius(8)
                .style(style)
                .hover(hovered ? 1f : 0f)
                .focus(isSelected ? 1f : 0f)
                .render();

        String displayName = summary.getLevelName();
        String name = summary.getLevelId();
        long lastPlayed = summary.getLastPlayed();
        if (lastPlayed != -1L) {
            name = name + " (" + DATE_FORMAT.format(Instant.ofEpochMilli(lastPlayed)) + ")";
        }

        if (displayName == null || displayName.isEmpty()) {
            displayName = Component.translatable("selectWorld.world").getString() + " " + (index + 1);
        }

        Component details = summary.getInfo();

        context.drawString(client.font, displayName, x + 40, y + 2, 0xFFFFFFFF);
        context.drawString(client.font, name, x + 40, y + 10 + 3, 0xFF808080);
        context.drawString(client.font, details, x + 40, y + 10 + 9 + 3, 0xFF808080);

        ResourceLocation texture = this.iconTexture != null ? this.iconId : DEFAULT_ICON_ID;
        context.blit(texture, x + 2, y + 2, 0, 0, 32, 32, 32, 32);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseOver(mouseX, mouseY)) {
            parent.getList().setSelected(this);
            return true;
        }
        return false;
    }

    public LevelSummary getSummary() {
        return this.summary;
    }

    @Override
    public void close() {
        if (this.iconTexture != null) {
            this.client.getTextureManager().release(this.iconId);
            this.iconTexture.close();
            this.iconTexture = null;
        }
    }
}