# 复制预览

面向 Android 16 / ColorOS 16.1 的 LSPosed 模块，复用系统左下角复制预览，使用 ColorOS 的 Oplus 材质模糊。

下载：[最新发布](https://github.com/Avi7ii/clipboard-glass/releases/latest) · [1.6.2 APK](https://github.com/Avi7ii/clipboard-glass/releases/download/v1.6.2/clipboard-glass-1.6.2.apk) · [SHA-256 校验文件](https://github.com/Avi7ii/clipboard-glass/releases/download/v1.6.2/SHA256SUMS)。Release 同时提供整理后的源码包和 APK 审计摘要。

包名：`dev.thom.clipboardglass`。需要支持 libxposed API 101 的 LSPosed。作用域固定为 `com.android.systemui` 主进程。

当前版本已安装到目标手机并加载。设置提供总开关、高级模糊、圆角、材质遮罩、显示时长、浮窗大小、预览字号与字重。显示时长为 1.0–5.0 秒，步进 0.1 秒；没有预设档位、常驻选项或自定义长时长。

浮窗固定在屏幕左下角，忽略键盘高度，键盘弹出时仍保留完整预览，文字与操作图标始终按背景亮度选择黑色或白色。移除 Send to device。每次复制产生独立的固定截止时间，回桌面和点击背景均不改变它。卡片可向任意方向拖动，松手位移达到 32 dp 后划走，否则回弹。

1.6.0 恢复完整的系统复制回调和原生点击监听。复制回调中仅放行一次国内版本的预览可用性检查；不自行读取、写入或清空剪贴板。点击时由原生代码创建并启动编辑页，确认实际编辑窗口恢复并获得焦点后关闭浮窗；启动失败时保留原倒计时。手势回桌面后的短暂启动限制通过 SystemUI 的既有权限解除，不再使用自建 PendingIntent 或查询任务列表。

1.6.2 固定屏幕左下角：复制浮窗窗口使用 SOFT_INPUT_ADJUST_NOTHING，清除会使非焦点窗口位于键盘下方的 ALT_FOCUSABLE_IM；保留 NOT_FOCUSABLE。所有原生预览布局入口都只使用稳定系统栏与屏幕切口边距，忽略 IME 高度，不再捕获可能已被键盘抬高的旧边距。键盘展开、收起均不抬高卡片。全向拖动仍跟随手指，未达到关闭阈值时回到原位。

目标手机的输入法历史缺失已另行定位：微信输入法的 READ_CLIPBOARD 为 ColorOS mode=5，系统会返回空剪贴板。仅将该应用这一项权限恢复为 allow 后，用户确认新复制内容可见，输入法数据库也出现新记录。这项手机权限修复不写进模块，不修改 Root 或全局隐藏设置。

最新验证范围与尚待实测的行为见 [RUNTIME_VERIFICATION.md](RUNTIME_VERIFICATION.md)。

## 编译

```sh
python3 build.py
```

使用本机 JDK 17、Android SDK 36 与 build-tools 36.1.0。可设置 `ANDROID_SDK_ROOT` 和 `CLIPBOARD_JDK`。依赖来自 Maven Central，SHA-256 固定在构建脚本里。libxposed API 101.0.1 仅用于编译；运行时包含 libxposed service/interface 101.0.0 和 Kotlin stdlib 2.2.10，以使用框架的私有设置服务。签名密钥只保存在本地 `signing/`（请保留，以便以后更新）。

## 安装与撤回

目标手机已按用户授权把模块包名加入原有 HMA 隐藏列表，并在 LSPosed 激活；独立核对后，除新增包名外，HMA 原配置完全一致。其它手机需先按自己的隐藏策略处理这个新包，再安装、激活并重启 SystemUI。应用内总开关立即关闭预览；关闭 LSPosed 模块并重启 SystemUI 可撤回全部 hook，卸载 APK 移除模块。模块运行时不写系统文件、Root 配置、隐藏属性或挂载目录。

不要同时打开 LuckyTool 的“禁用复制悬浮窗”；其它复制预览 hook 也可能冲突。

高级模糊开关沿用系统的 `system_material_blur_enable`；系统关闭模糊或跨窗口模糊不可用时，使用 OEM 降级背景。图像预览保留原图，不把图像内容做模糊。

适配基于目标手机自带的 SystemUI 和 ColorOS 接口，[设计说明](DESIGN.md)列出实现与隐私边界。仓库及发布源码压缩包不含厂商 APK、签名密钥、设备配置、设备日志或截图。

依赖组件与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。模块运行依赖现有 LSPosed 和设备自带的 ColorOS 类，不携带它们的实现。
