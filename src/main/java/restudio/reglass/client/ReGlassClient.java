package restudio.reglass.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import restudio.reglass.client.api.WidgetStyle;
import restudio.reglass.client.config.ReGlassSettingsIO;
import restudio.reglass.client.screen.config.ReGlassConfigScreen;

public final class ReGlassClient {
    private static KeyMapping playgroundKey;
    private static KeyMapping configKey;

    public static Minecraft minecraftClient;

    public static void init(IEventBus modEventBus) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraftClient = minecraft;

        playgroundKey = new KeyMapping("ReGlass Playground", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "Liquid Glass");
        configKey = new KeyMapping("ReGlass Config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "Liquid Glass");

        modEventBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(playgroundKey);
            event.register(configKey);
        });

        ReGlassSettingsIO.loadIntoMemory();

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Minecraft client = Minecraft.getInstance();
            while (configKey.consumeClick()) {
                client.setScreen(new ReGlassConfigScreen(null));
            }
            while (playgroundKey.consumeClick()) {
                client.setScreen(new PlaygroundScreen());
            }
        });
    }

    public static class PlaygroundScreen extends Screen {
        private boolean blur;
        private WidgetStyle customStyle;

        public PlaygroundScreen() {
            super(Component.literal("ReGlass Playground"));
        }

        @Override
        protected void init() {
            super.init();

            Integer goldColor = ChatFormatting.GOLD.getColor();
            customStyle = WidgetStyle.create().tint(goldColor != null ? goldColor : 0xFFA500, 0.4f).blurRadius(0).shadow(25f, 0.2f, 0f, 3f).smoothing(.05f).shadowColor(0x000000, 1.0f);
            addRenderableWidget(new LiquidGlassWidget(width / 2 - 75, height / 2 - 25, 150, 50, customStyle).setMoveable(true));
            addRenderableWidget(Button.builder(Component.literal("Toggle BG Blur"), b -> blur = !blur).bounds(10, 10, 120, 20).build());
        }

        @Override
        public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
            context.drawString(minecraftClient.font, Component.literal("This is a Minecraft Screen"), width / 2 - 70, 10, 0xFFFFFFFF, true);
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
            if (blur) super.renderBackground(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 1) {
                addRenderableWidget(new LiquidGlassWidget((int) mouseX - 50, (int) mouseY - 50, 100, 100, WidgetStyle.create().smoothing(.05f))).setMoveable(true);
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
}
