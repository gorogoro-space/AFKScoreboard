# AFKScoreboard

AxAFKZone の放置ゾーンにいるプレイヤーを、今週の累計でサイドバーに並べる Paper 用プラグイン。連続放置時間はメモリ上で別途数える。
リポジトリ: <https://github.com/gorogoro-space/AFKScoreboard>(ライセンス: LGPL v3)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。コード内のコメントやメッセージも日本語。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と docs/spec.md も合わせて更新する。下の「機能の一覧」が変わるときは CLAUDE.md も更新する。
- `git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- リポジトリへの初回の `git push` の前には、「GitHub で Watch 設定(Custom → Issues と Pull requests)はしましたか?」と日本語で確認する。自動 Watch は廃止されており、設定しないと issue や PR の通知が届かない。
- コミットメッセージや PR に `Co-Authored-By: Claude` などの署名を付けない。`.claude/` は Git に入れない(`.git/info/exclude` で除外する)。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。
- プルリクエストをチェックするときは、docs/review.md の観点で確認して報告する。

## 環境

- Paper 1.21.11 / Java 21
- ビルド: `gradlew.bat clean build`(Windows)。「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/`
- パッケージ: `space.gorogoro.afkscoreboard`(**すべて小文字**。大文字が混ざると plugin.yml の main と一致せず起動しない)
- PlugManX での再読み込み(`/plugman reload AFKScoreboard`)を前提とする。動作確認も PlugManX で行ってよい。ただし権限やコマンドの登録が不完全になることがあるので、おかしな挙動があればサーバーを再起動して確かめる

## 最重要の設計方針

### TPS に影響させない
- 高頻度イベント(BlockFromToEvent、EntityChangeBlockEvent、PlayerInteractEvent、PlayerMoveEvent など)は、安い判定(ワールドや座標の整数比較など)を先に行い、対象外なら即座に抜ける
- BlockPhysicsEvent、VehicleMoveEvent など発生頻度が極端に高いイベントは使わない
- メインスレッドでファイルや DB の同期 I/O をしない(ただし、起動時・リロード時に一度だけ行う小さなファイルの読み込みは除く)。未読み込みチャンクを判定のために読み込まない(`getChunkAtAsync`、`teleportAsync` を使う)
- config.yml は起動時・リロード時に一度だけ解析して保持する。Material などの集合は EnumSet
- 定期タスクは最小限にし、追加するときは頻度と理由を設計案に書く

### データの保存
- DB やファイルの読み書きはメインスレッド以外(専用スレッドなど)で行う。書き込みはまとめて反映する
- 判定はメモリ上のデータだけで行う。必要なデータだけを必要なときに読み込む
- プラグインフォルダ以外には何も書き込まない

### PlugManX での再読み込みに対応する
- `onDisable` で後始末をし、アンロード後に状態を残さない(プレイヤーのスコアボードを戻す、自前のスレッドを止める など)
- `onEnable` は、プレイヤーがすでにオンラインでゾーン内にいる状態から始まっても正しく動くようにする
- 連続放置時間と回線落ち救済のデータはメモリ上だけに持つため、再読み込みでリセットされる(仕様)

### 他プラグインとの役割分担(このプラグインでは扱わない)
- 放置ゾーンの作成・管理、放置報酬の付与 → AxAFKZone(本プラグインは `plugins/AxAFKZone/zones/*.yml` を読み取るだけで、書き込まない)
- 既存の機能(例: GSit、看板の click_event など)を妨げないこと。イベントをキャンセルする範囲は必要最小限にする

## 機能の一覧

- 見た目・頭の MOB・名前・統合版・テレポートに関わる変更や調査をするときは、作業の前に必ず docs/spec.md と docs/pitfalls.md を読む
- ゾーンの読み込み、ボードの表示、連続放置時間、ランキング、週間累計、回線落ち救済
- コマンド: `/afkhide`・`/afklook`(全員)、`/afkdebug`(OP)
- スペクテイター・バニッシュ中の人には、ランキング・見た目・ボードを出さない
- 見た目ボーナス: 30 分でパーティクル、1 時間でブロック、3 時間で頭の MOB
- 保存データ: config.yml・data.yml・cosmetics.yml(書き込みは専用スレッド)

## 過去にハマった点

- 不具合を調べるときや、上の「必ず読む」場面では、docs/pitfalls.md を読む

## ファイル構成(src/main/java/space/gorogoro/afkscoreboard/)

- `AFKScoreboard.java` — メインクラス。ゾーン読み込み、スコアボード、イベント、`/afkhide` コマンド、データ保存をすべて持つ(ゾーン範囲は内部クラス `ZoneArea`)
- `WeeklyStore.java` — 今週の累計秒数と `data.yml` の非同期保存
- `CosmeticService.java` — ゾーン内の見た目。停止時の後始末もここ
- `CosmeticStore.java` — 見た目用の今週の秒数と抽選、`cosmetics.yml` の非同期保存
- `CosmeticKinds.java` — パーティクル、ブロック、頭 MOB の種類
- `BedrockPlayers.java` — 統合版の人の判定(Floodgate API。Floodgate が無ければ使わない)
- `src/main/resources/plugin.yml` — プラグイン定義、コマンド定義
- `src/main/resources/config.yml` — 保存データ(`welcomed-players`、`hidden-players`)と、週の区切り(`timezone`、`week-start-day`)、乗客を降ろさないテレポートコマンド(`teleport-commands`)
- `docs/spec.md` — 機能仕様の詳細(見た目・統合版・テレポートの経緯を含む)
- `docs/pitfalls.md` — 過去にハマった点
- `docs/review.md` — プルリクエストを確認するときの観点

