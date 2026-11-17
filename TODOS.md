# TODOS

- [ ] 继续补充长时间Retry-After、后台限制与开销分布；已有Android13实体短等待接续、主动重入、轨迹附带及独立QA崩溃/renderer恢复验证，不能替代长期休眠和真实OOM。

只保留当前未完成事项；已验证经验见 NOTES，历史验收与本轮定向验证见 HANDOFF。

## 可靠性与真机验收

- [ ] BOOX 的本机隔离链路已验证；仍需真实断网与 NetworkType 约束、长时间休眠/后台限制场景。503/临时失败模拟不能替代物理断网结论。
- [ ] 扩展崩溃耗时和极端低内存成功率采样：现有 BOOX 单次约48.8ms，不能当作 p95 或低内存上界。
- [ ] 书禾 WebView 的常规 TXT 场景已确认位置恢复；其他格式、阅读状态与仅重建 WebView 的性能方案仍待评估。
- [ ] Android 14+ 内存诊断方案：旧 onTrimMemory 等级不再发送，评估进程退出信息等补充信号，不能把 UI_HIDDEN 或脏退出直接标为 OOM。

## 后续能力

- [ ] capture() 在宿主导入/解析/同步失败处接线；先限定可上传字段，不能传用户内容或业务凭据。
- [x] `tools/upload-mapping.mjs` 已切换为「cloud 只签票据、文件直传七牛」三步（tickets → 七牛直传 → confirm，kind=mapping），旧 multipart 上传已移除，`node --check` 通过；旧版曾用真实 android token 验证 `symbols/check` 与上传。**待书禾实际开启 minify 后做真实直传联调并接入 CI**（当前 `isMinifyEnabled=false`，无 mapping 文件）。
- [ ] 多进程支持：**已评估，暂不做**——当前单进程假设（只允许主进程安装，不能让多个进程共写同一文件）；真正的多进程需 IPC 汇总与按进程分文件，成本高，宿主要求保持单进程。
- [ ] gzip 上报：待 `moo-scaffold-cloud` intake 确认支持 `Content-Encoding: gzip` 后再实现（可显著减小上传体积，但要服务端先支持）。
