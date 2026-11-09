(ns marquee.lib.utils
  "The shadcn `cn` helper: merge class names with clsx, then resolve
  conflicting Tailwind classes with tailwind-merge."
  (:require [clojure.string :as str]
            ["clsx" :refer [clsx]]
            ["tailwind-merge" :refer [twMerge]]))

(defn cn [& inputs]
  (twMerge (clsx (clj->js inputs))))

(defn media-display-name
  "A descriptive name for a media item, qualified by its ancestors:
  \"<name> - <parent> - <grandparent> …\". `items` is the media-items cache
  (id → item) used to resolve parents. Episode names like \"Episode 13\" become
  \"Episode 13 - Season 2 - The Show\". Gracefully falls back to just the item's
  own name when its parents aren't loaded (or it has none, e.g. a movie).
  Returns nil when the item or its name is missing."
  [item items]
  (when item
    (let [names (loop [cur item, acc [], seen #{}]
                  (if (or (nil? cur) (contains? seen (:id cur)))
                    acc
                    (let [nm  (:name cur)
                          acc (if (and (string? nm) (not (str/blank? nm)))
                                (conj acc nm)
                                acc)]
                      (recur (get items (:parent-id cur)) acc (conj seen (:id cur))))))]
      (when (seq names)
        (str/join " - " names)))))

(defn event-guide-title
  "Title for a playout event from the display fields Pseudovision puts on
  each event (:title plus :parent-title / :grandparent-title, i.e. season and
  show for an episode), joined like `media-display-name`. nil for an event
  from an older Pseudovision that doesn't send them (no :item-kind key)."
  [ev]
  (when (contains? ev :item-kind)
    (not-empty (str/join " - " (remove str/blank? [(:title ev) (:parent-title ev)
                                                   (:grandparent-title ev)])))))

(defn event-library
  "{:key :label} for the library of the item a playout event plays, from the
  event's own :library-id / :library-name; nil when it doesn't carry them."
  [{:keys [library-id library-name]}]
  (when (some? library-id)
    {:key (str "lib:" library-id) :label (or library-name (str "Library " library-id))}))
