Vector 2.1 is the first release built on **libxposed API 101**, the newly published standard, and it ships with a manager rebuilt from scratch and support for the latest Android platforms.

Where 2.0 was the definitive close of the API 100 era, 2.1 opens the next one: the framework, the daemon, and the manager have all moved to API 101.

### 🧩 libxposed API 101
With API 101 now published, the ecosystem's new standard brings significant breaking changes. Vector 2.1 migrates the entire framework onto it, adapting to the changed package-query and reflection contracts so modules written against the current API behave as their authors intend.

### 🎨 A New Manager, Rebuilt in Compose
The manager has been rewritten from the ground up in Jetpack Compose, and its design is community-centred: an interface built to take the everyday experience to the next level, shaped by my own aesthetic taste and open to yours.

Our mascot is *[The Winged Victory of Samothrace](https://en.wikipedia.org/wiki/Winged_Victory_of_Samothrace)* — because Vector and Victory are never far apart. We warmly welcome feedback on the new design so we can keep refining this front end together.

### 📱 Expanded Platform Support
*   🤖 **Android 17:** Full support for the latest Android release, extending Vector's range to Android 8.1 through 17.
*   🛡️ **GrapheneOS:** The parasitic manager now loads correctly under GrapheneOS's hardened dynamic-code-loading restrictions.
*   💾 **16 KB Page Sizes:** Native support for devices that ship with 16 KB memory pages.

---

*A personal note: I, JingMatrix, have founded my own consultancy, **[Matrix Transformation](https://www.linkedin.com/in/jingmatrix/)**, and now work as a full-time entrepreneur. Nurturing this community is one of the company's most important goals — it is how I realize my own long-held pursuit of serving you — and so all of my open-source projects, Vector included, are funded by the company. Thank you for being part of this journey.*
