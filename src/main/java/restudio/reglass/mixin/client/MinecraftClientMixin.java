package restudio.reglass.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import restudio.reglass.client.api.ReGlassConfig;
import restudio.reglass.client.screen.world.CustomWorldSelectScreen;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    @Shadow
    public abstract void setScreen(@Nullable Screen screen);

    @Shadow
    @Nullable
    public Screen screen;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screenParam, CallbackInfo ci) {
        if (ReGlassConfig.INSTANCE.features.enableRedesign && false) { //Commited by mistake last time, heavy WIP.
            if (screenParam instanceof SelectWorldScreen) {
                setScreen(new CustomWorldSelectScreen(screen));
                ci.cancel();
            }
        }
    }
}