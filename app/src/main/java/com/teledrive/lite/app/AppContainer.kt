package com.teledrive.lite.app

import android.content.Context

/**
 * 进程级依赖容器。后续任务会在这里注册数据库、网络客户端和仓库。
 */
class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
}
