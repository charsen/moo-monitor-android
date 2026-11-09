# 库自身不需要额外 keep 规则；崩溃处理链通过 Thread.setDefaultUncaughtExceptionHandler 安装，
# 不涉及反射。宿主开启 minify 时，堆栈混淆交给云端 mapping 还原（M2）。
