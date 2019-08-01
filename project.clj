(defproject eazy-f/shelf "0.0.1"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.520"]
                 [org.clojure/core.async "0.4.474"]
                 [binaryage/chromex "0.8.1"]
                 [binaryage/devtools "0.9.10"]
                 [com.bhauman/figwheel-main "0.2.3"]
                 [cider/piggieback "0.4.1"]
                 [environ "1.1.0"]]

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-figwheel "0.5.19"]
            [lein-shell "0.5.0"]
            [lein-environ "1.1.0"]
            [lein-cooper "1.2.2"]]

  :source-paths ["src/background",
                 "src/popup"]

  :clean-targets ^{:protect false} ["target"
                                    "resources/unpacked/compiled"
                                    "resources/release/compiled"]

  :cljsbuild {:builds {}} ; prevent https://github.com/emezeske/lein-cljsbuild/issues/413

  
  :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}
  :profiles {:unpacked
             {:cljsbuild
              {:builds
               {:background
                {:source-paths ["src/background"]
                 :compiler     {:output-to     "resources/unpacked/compiled/background/main.js"
                                :output-dir    "resources/unpacked/compiled/background"
                                :asset-path    "compiled/background"
                                :preloads      [devtools.preload figwheel.preload]
                                :main          shelf.background
                                :optimizations :none
                                :external-config {:figwheel/config
                                                  {:websocket-url "ws://localhost:6888/figwheel-ws"
                                                   :websocket-host "localhost"
                                                   :websocket-port 6888}}
                                :foreign-libs [{:file "https://www.gstatic.com/firebasejs/6.3.3/firebase.js"
                                                :provides ["firebase"]}]
                                :source-map    true}}
                :popup
                {:source-paths ["src/popup"]
                 :compiler     {:output-to     "resources/unpacked/compiled/popup/main.js"
                                :output-dir    "resources/unpacked/compiled/popup"
                                :asset-path    "compiled/popup"
                                :preloads      [devtools.preload figwheel.preload]
                                :main          shelf.popup
                                :optimizations :none
                                :external-config {:figwheel/config
                                                  {:websocket-url "ws://localhost:6888/figwheel-ws"
                                                   :websocket-host "localhost"
                                                   :websocket-port 6888}}
                                :source-map    true}}}}}
             :figwheel
             {:figwheel {:server-port    6888
                         :server-logfile ".figwheel.log"
                         :repl           true}}

             :disable-figwheel-repl
             {:figwheel {:repl false}}

             :cooper
             {:cooper {"fig-dev-no-repl" ["lein" "fig-dev-no-repl"]
                       "browser"         ["scripts/launch-test-browser.sh"]}}

             :release
             {:env       {:chromex-elide-verbose-logging "true"}
              :cljsbuild {:builds
                          {:background
                           {:source-paths ["src/background"]
                            :compiler     {:output-to     "resources/release/compiled/background.js"
                                           :output-dir    "resources/release/compiled/background"
                                           :asset-path    "compiled/background"
                                           :main          chromex-sample.background
                                           :optimizations :advanced
                                           :elide-asserts true}}}}}}

  :aliases {"dev-build"       ["with-profile" "+unpacked" "cljsbuild" "once"]
            "fig"             ["with-profile" "+unpacked,+figwheel" "figwheel" "background" "popup"]
            "fig-dev-no-repl" ["with-profile" "+unpacked,+figwheel,+disable-figwheel-repl,+checkouts" "figwheel" "background"]
            "devel"           ["with-profile" "+cooper" "do"                                                                  ; for mac only
                               ["shell" "scripts/ensure-checkouts.sh"]
                               ["cooper"]]
            "release"         ["with-profile" "+release" "do"
                               ["clean"]
                               ["cljsbuild" "once" "background"]]
            "package"         ["shell" "scripts/package.sh"]})
