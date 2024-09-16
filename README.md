# LSPosed Framework

[![Build](https://img.shields.io/github/actions/workflow/status/LSPosed/LSPosed/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/LSPosed/LSPosed/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted) [![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://lsposed.crowdin.com/lsposed) [![Channel](https://img.shields.io/badge/Follow-Telegram-blue.svg?logo=telegram)](https://t.me/LSPosed) [![Chat](https://img.shields.io/badge/Join-QQ%E9%A2%91%E9%81%93-red?logo=tencent-qq&logoColor=red)](https://qun.qq.com/qqweb/qunpro/share?_wv=3&_wwv=128&inviteCode=Xz9dJ&from=246610&biz=ka) [![Download](https://img.shields.io/github/v/release/LSPosed/LSPosed?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/re-zero001/LSPosed/releases/latest) [![Total](https://shields.io/github/downloads/LSPosed/LSPosed/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/LSPosed/LSPosed/releases)

## Introduction 

A Zygisk module trying to provide an ART hooking framework which delivers consistent APIs with the OG Xposed, leveraging LSPlant hooking framework.

> Xposed is a framework for modules that can change the behavior of the system and apps without touching any APKs. That's great because it means that modules can work for different versions and even ROMs without any changes (as long as the original code was not changed too much). It's also easy to undo. As all changes are done in the memory, you just need to deactivate the module and reboot to get your original system back. There are many other advantages, but here is just one more: multiple modules can do changes to the same part of the system or app. With modified APKs, you have to choose one. No way to combine them, unless the author builds multiple APKs with different combinations.

## Supported Versions

Android 8.1 ~ 15

## Install

1. Install Magisk v27+
2. Install [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext/releases)
> No need enable zygisk in magisk.
3. [Download](#download) and install LSPosed in Magisk app
4. Reboot
5. Open LSPosed manager from notification
6. Have fun :)

## Download

- For stable releases, please go to [Github Releases page](https://github.com/re-zero001/LSPosed/releases)

## Get Help

- Not support issues,because I don't know how to fix.

## For Developers

Developers are welcome to write Xposed modules with hooks based on LSPosed Framework. A module based on LSPosed framework is fully compatible with the original Xposed Framework, and vice versa, a Xposed Framework-based module will work well with LSPosed framework too.

- [Xposed Framework API](https://api.xposed.info/)

We use our own module repository. We welcome developers to submit modules to our repository, and then modules can be downloaded in LSPosed.

- [LSPosed Module Repository](https://github.com/Xposed-Modules-Repo)

## Community Discussion

- Telegram: [@LSPosed](https://t.me/s/LSPosed)

Notice: These community groups don't accept any bug report, please use [Get help](#get-help) to report.

## Translation Contributing

You can contribute translation [here](https://lsposed.crowdin.com/lsposed).

## Credits 

- [Magisk](https://github.com/topjohnwu/Magisk/): makes all these possible
- [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext): provides a way to inject code into zygote process
- [XposedBridge](https://github.com/rovo89/XposedBridge): the OG Xposed framework APIs
- [Dobby](https://github.com/jmpews/Dobby): used for inline hooking
- [LSPlant](https://github.com/LSPosed/LSPlant): the core ART hooking framework
- [LSPosed](https://github.com/LSPosed/LSPosed): fork source
- [xz embedded](https://github.com/tukaani-project/xz-embedded):decompress debug_info section into stripped libraries
- [system_properties](https://github.com/topjohnwu/system_properties):switch properties access within LSPosed
- ~[SandHook](https://github.com/ganyao114/SandHook/): ART hooking framework for SandHook variant~
- ~[YAHFA](https://github.com/rk700/YAHFA): previous ART hooking framework~
- ~[dexmaker](https://github.com/linkedin/dexmaker) and [dalvikdx](https://github.com/JakeWharton/dalvik-dx): to dynamically generate YAHFA hooker classes~
- ~[DexBuilder](https://github.com/LSPosed/DexBuilder): to dynamically generate YAHFA hooker classes~

## License

LSPosed is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
