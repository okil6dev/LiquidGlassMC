# ReGlass 前景分层与合成方案

本文档定义 ReGlass 的固定 GUI 分层方案。目标是保证文字、Logo、图标和其他界面图片不会进入玻璃模糊采样，同时保留按钮背景的折射、模糊和高光效果。

## 固定渲染顺序

每帧按照以下顺序执行：

1. 绘制场景或标题界面的全景背景到 Minecraft 主帧缓冲。
2. 清空 ReGlass 透明前景 FBO。
3. 在正常 GUI 投影下，将文字、Logo、图标和其他前景图片绘制到透明前景 FBO。
4. 按钮背景不写入前景 FBO。`DrawContextMixin` 取消原版按钮背景，并将按钮矩形登记到玻璃 Widget 列表。
5. `LiquidGlassPrecomputeRuntime` 只从主帧缓冲生成模糊纹理，因此采样源不包含 GUI 前景。
6. `GameRendererMixin` 将玻璃效果绘制到主帧缓冲。
7. `LiquidGlassForegroundRuntime` 使用预乘 Alpha 将透明前景 FBO 一次性合成到玻璃之上。

最终层级为：

```text
文字 / Logo / 图标 / GUI 图片
玻璃按钮背景
模糊后的场景或全景背景
```

## 标题界面规则

标题界面的分层边界固定在 `TitleScreen.renderPanoramaBackground()` 之后：

- 全景背景保留在主帧缓冲中，作为玻璃采样源。
- 在全景背景绘制和背景模糊完成后，开启整层前景捕获。
- `Screen.render()`、Minecraft Logo、黄色标语、版本信息、版权信息、按钮文字以及语言/辅助功能图标全部写入透明前景 FBO。
- 标题界面渲染结束时关闭捕获，等待帧尾合成。
- 按钮背景仍由玻璃着色器生成，不写入透明前景 FBO。

因此，标题界面中除按钮背景之外的所有 GUI 内容都不会进入模糊纹理。

## 普通 Widget 规则

`ClickableWidgetMixin` 在以下 Widget 的 `render()` 边界自动开启和关闭前景捕获：

- 启用按钮重设计时的 `PressableWidget`。
- 启用 Slider 重设计时的 `SliderWidget`。

捕获覆盖完整 Widget 渲染过程，而不是只拦截某个文字方法。这样按钮子类以后新增的图标、纹理或其他前景绘制也会自动进入前景 FBO。

玻璃背景的登记仍发生在捕获期间，但 `ReGlassApi` 只记录几何信息，不向当前 FBO 输出像素。

## 运行时职责

`LiquidGlassForegroundRuntime` 是前景层的唯一所有者，负责：

- 根据主帧缓冲尺寸创建和重建透明 FBO。
- 每帧第一次捕获前清空透明 FBO。
- 保存并恢复原 Framebuffer 与 Viewport。
- 支持嵌套捕获，只有最外层捕获切换 Framebuffer。
- 在玻璃完成后执行预乘 Alpha 合成。
- 重置 `BufferRenderer` 的 VAO 缓存，避免原始 OpenGL 绘制与 Minecraft 渲染缓存不同步。

`LiquidGlassUniforms` 只负责 Widget 和 Uniform 数据，不再管理前景绘制或 Framebuffer。

## Alpha 约定

透明前景 FBO 中的 GUI 内容由 Minecraft 原生混合流程写入，颜色按 Alpha 预乘。最终合成固定使用：

```text
source factor = ONE
destination factor = ONE_MINUS_SRC_ALPHA
```

不要改成 `SRC_ALPHA / ONE_MINUS_SRC_ALPHA`，否则文字和图标会重复乘 Alpha，边缘会变暗。

## 新增界面元素

标题界面中新增的任何 GUI 内容，只要绘制发生在全景背景之后，就会自动进入前景层，不需要新增 Mixin。

其他界面新增玻璃 Widget 时，应在该 Widget 的完整 `render()` 边界开启前景捕获，而不是只延迟文字。需要捕获一组非 Widget 绘制时，使用：

```java
LiquidGlassForegroundRuntime foreground = LiquidGlassForegroundRuntime.get();
foreground.beginCapture();
try {
    // DrawContext foreground calls
} finally {
    foreground.endCapture();
}
```

必须保证 `beginCapture()` 和 `endCapture()` 成对出现。

## 不变量

- 模糊预计算开始前，主帧缓冲中不得包含需要保持清晰的 GUI 前景。
- 玻璃绘制开始前，所有前景捕获必须结束。
- 前景合成只能发生在玻璃绘制之后。
- 按钮背景只能由玻璃管线输出，不能同时保留原版按钮纹理。
- 全屏四边形顶点顺序必须完整覆盖 FBO，纹理采样必须钳制到合法 UV 范围。

