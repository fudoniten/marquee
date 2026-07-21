(ns marquee.views
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.pages.home :as home]
            [marquee.pages.media :as media]
            [marquee.pages.media-detail :as media-detail]
            [marquee.pages.browse :as browse]
            [marquee.pages.grout-detail :as grout-detail]
            [marquee.pages.api-docs :as api-docs]
            [marquee.pages.schedule :as schedule]
            [marquee.pages.jobs :as jobs]
            [marquee.pages.collections :as collections]))

(def pages
  {:home             {:label "Home"     :view home/page          :show-in-nav true}
   :media            {:label "Media"    :view media/page         :show-in-nav true}
   :browse           {:label "Browse"   :view browse/page        :show-in-nav true}
   :api-docs         {:label "API Docs" :view api-docs/page      :show-in-nav true}
   :schedule-grid    {:label "Guide"    :view schedule/grid-page :show-in-nav true}
   :collections      {:label "Collections" :view collections/page :show-in-nav true}
   :jobs             {:label "Jobs"     :view jobs/page          :show-in-nav true}
   :channel-schedule {:label "Schedule" :view schedule/channel-page :show-in-nav false}
   :media-detail     {:label "Media Detail" :view media-detail/page :show-in-nav false}
   :grout-detail     {:label "Grout Item" :view grout-detail/page :show-in-nav false}
   :collection-detail {:label "Collection" :view collections/page :show-in-nav false}})

(defn navbar []
  (let [active @(rf/subscribe [::subs/active-page])]
    [:nav {:class "flex flex-wrap items-center gap-1 border-b border-border pb-4"}
     [:span {:class "mr-4 font-semibold text-primary tracking-tight"} "Marquee"]
     (for [[page {:keys [label show-in-nav]}] pages
           :when show-in-nav]
       ^{:key page}
       [button {:variant (if (or (= page active)
                              (and (= page :collections) (= active :collection-detail)))
                          :secondary :ghost)
                :size :sm
                :on-click #(rf/dispatch [::events/navigate page])}
        label])]))

(defn loading []
  [:div {:class "flex items-center justify-center h-screen text-muted-foreground"}
   "Loading…"])

(defn- short-commit
  "First 7 chars of a git sha, or the value as-is when it's shorter (e.g. Nix's
   versionInfo falls back to the literal string \"unknown\" when the flake has
   no `self.rev`/`self.dirtyRev`) — showing that verbatim is more useful than
   hiding it, since it tells the deployer their build has no git metadata."
  [commit]
  (when commit
    (if (>= (count commit) 7) (.slice commit 0 7) commit)))

(defn- format-build-date
  "Nix's versionInfo timestamp is a YYYYMMDD string (build date, no
   time-of-day) — render it as YYYY-MM-DD. Passes through unrecognized
   shapes (e.g. the \"dev\" fallback used in a flake with no lastModified)
   unchanged rather than mangling them."
  [ts]
  (when ts
    (if (re-matches #"\d{8}" ts)
      (str (.slice ts 0 4) "-" (.slice ts 4 6) "-" (.slice ts 6 8))
      ts)))

(defn footer
  "Build identity (version tag / commit / build date), so it's obvious from
   any page whether a given change has actually reached the running
   deployment — no dedicated /api/version endpoint needed. Renders nothing
   until /api/config resolves, and nothing at all in local dev (`clojure
   -M:server`), where GIT_COMMIT/GIT_TIMESTAMP/VERSION are never set."
  []
  (let [{:keys [git-commit git-timestamp version]} @(rf/subscribe [::subs/build-info])]
    (when (or git-commit git-timestamp version)
      [:footer {:class "mt-12 border-t border-border pt-4 text-xs text-muted-foreground flex flex-wrap items-center gap-x-3 gap-y-1"}
       (when version [:span version])
       (when-let [c (short-commit git-commit)]
         [:span {:title git-commit} (str "commit " c)])
       (when-let [d (format-build-date git-timestamp)]
         [:span (str "built " d)])])))

(defn app []
  (let [active  @(rf/subscribe [::subs/active-page])
        ready?  @(rf/subscribe [::subs/api-ready?])
        view    (get-in pages [active :view])]
    (if-not ready?
      [loading]
      ;; A single, consistent wide container for every tab so nothing jumps
      ;; around when switching pages. Responsive padding keeps it usable on
      ;; phones while filling a desktop window.
      [:div {:class "mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8 py-8"}
       [navbar]
       [:main {:class "py-8"}
        [view]]
       [footer]])))
