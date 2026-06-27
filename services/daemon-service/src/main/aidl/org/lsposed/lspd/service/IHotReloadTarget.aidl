package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;

interface IHotReloadTarget {
    void hotReloadModule(in Module module, in Bundle extras);
}
