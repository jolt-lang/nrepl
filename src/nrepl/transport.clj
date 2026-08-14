(ns nrepl.transport
  "Client-side bencode transport over a TCP socket bound through jolt.ffi.
  `connect` opens a connection to a running nREPL server; `send`/`recv` move
  bencode messages. The server side lives in jolt core (jolt.nrepl)."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [nrepl.bencode :as bencode]))

(ffi/load-library)
(ffi/defcfn c-socket       "socket"       [:int :int :int] :int)
(ffi/defcfn c-connect      "connect"      [:int :pointer :int] :int :blocking)
(ffi/defcfn c-close        "close"        [:int] :int)
(ffi/defcfn c-recv         "recv"         [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-send         "send"         [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-getaddrinfo  "getaddrinfo"  [:pointer :pointer :pointer :pointer] :int :blocking)
(ffi/defcfn c-freeaddrinfo "freeaddrinfo" [:pointer] :void)

(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))
(def ^:private O-ai-family 4)
(def ^:private O-ai-socktype 8)
(def ^:private O-ai-protocol 12)
(def ^:private O-ai-addrlen 16)
(def ^:private O-ai-addr (if macos? 32 24))
(def ^:private O-ai-next 40)

(defn- raw-connect [host port]
  (let [node (ffi/string->ptr (str host))
        service (ffi/string->ptr (str port))
        respp (ffi/alloc (ffi/sizeof :pointer))
        hints (ffi/alloc 48)]
    (dotimes [i 48] (ffi/write hints :uint8 i 0))
    (ffi/write hints :int O-ai-socktype 1)            ; SOCK_STREAM
    (try
      (when-not (zero? (c-getaddrinfo node service hints respp))
        (throw (ex-info (str "nREPL connect: cannot resolve " host) {:host host})))
      (let [res (ffi/read respp :pointer)]
        (try
          (loop [ai res]
            (if (ffi/null? ai)
              (throw (ex-info (str "nREPL connect refused: " host ":" port) {:host host :port port}))
              (let [fd (c-socket (ffi/read ai :int O-ai-family) (ffi/read ai :int O-ai-socktype)
                                 (ffi/read ai :int O-ai-protocol))]
                (cond
                  (neg? fd) (recur (ffi/read ai :pointer O-ai-next))
                  (zero? (c-connect fd (ffi/read ai :pointer O-ai-addr) (ffi/read ai :int O-ai-addrlen))) fd
                  :else (do (c-close fd) (recur (ffi/read ai :pointer O-ai-next)))))))
          (finally (c-freeaddrinfo res))))
      (finally (ffi/free node) (ffi/free service) (ffi/free respp) (ffi/free hints)))))

(def ^:private bufsize 65536)

(ffi/defcfn c-setsockopt "setsockopt" [:int :int :int :pointer :int] :int)

(defn- set-recv-timeout!
  "SO_RCVTIMEO: recv returns instead of blocking forever once `secs` pass with
  no data. A struct timeval is two longs (or long+suseconds), so 16 zeroed
  bytes with the seconds in the first word covers both 64-bit layouts.
  SOL_SOCKET is 1 on Linux and 0xffff on the BSDs; SO_RCVTIMEO is 20 and
  0x1006 respectively."
  [fd secs]
  (let [[sol so] (if (= "Mac OS X" (System/getProperty "os.name"))
                   [0xffff 0x1006] [1 20])
        tv (ffi/alloc 16)]
    (try
      (dotimes [i 16] (ffi/write tv :uint8 i 0))
      (ffi/write tv :long 0 secs)
      (c-setsockopt fd sol so tv 16)
      (finally (ffi/free tv)))))

(defn connect
  "Open a connection to an nREPL server. Returns a transport (an opaque map).
  `:recv-timeout-secs` bounds every read: a server that stops replying turns
  into a nil message (connection treated as closed) instead of a caller
  blocked in recv forever."
  ([host port] (connect host port nil))
  ([host port {:keys [recv-timeout-secs]}]
   (let [fd (raw-connect host port)]
     (when recv-timeout-secs (set-recv-timeout! fd recv-timeout-secs))
     {:fd fd :buf (atom "") :lock (Object.)})))

(defn send
  "Send message map `msg` over `transport`."
  [{:keys [fd lock]} msg]
  (let [s (bencode/encode msg)
        data (byte-array (map int s)) n (alength data) buf (ffi/alloc (max 1 n))]
    (try
      (ffi/write-array buf data)
      (locking lock
        (loop [off 0]
          (when (< off n)
            (let [sent (c-send fd (+ buf off) (- n off) 0)]
              (when (pos? sent) (recur (+ off sent)))))))
      (finally (ffi/free buf)))
    nil))

(defn recv
  "Receive the next message from `transport`, blocking until one is available or
  the connection closes (then nil)."
  [{:keys [fd buf]}]
  (loop []
    (let [r (bencode/decode @buf 0)]
      (if r
        (do (swap! buf subs (second r)) (first r))
        (let [b (ffi/alloc bufsize)
              chunk (try (let [k (c-recv fd b bufsize 0)]
                           (when (pos? k) (String. (ffi/read-array b k) "ISO-8859-1")))
                         (finally (ffi/free b)))]
          (when chunk (swap! buf str chunk) (recur)))))))

(defn close [{:keys [fd]}] (c-close fd) nil)
