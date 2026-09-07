(ns nrepl.transport
  "Client-side bencode transport over a TCP socket bound through jolt.ffi.
  `connect` opens a connection to a running nREPL server; `send`/`recv` move
  bencode messages. The server side lives in jolt core (jolt.nrepl)."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.io-poller :as poller]
            [nrepl.bencode :as bencode]))

(ffi/load-library)
(ffi/defcfn c-socket       "socket"       [:int :int :int] :int)
(ffi/defcfn c-connect      "connect"      [:int :pointer :int] :int :blocking)
(ffi/defcfn c-close        "close"        [:int] :int)
(ffi/defcfn c-recv         "recv"         [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-send         "send"         [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn c-getaddrinfo  "getaddrinfo"  [:pointer :pointer :pointer :pointer] :int :blocking)
(ffi/defcfn c-freeaddrinfo "freeaddrinfo" [:pointer] :void)
(ffi/defcfn c-poll         "poll"         [:pointer :int :int] :int :blocking)

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
    (dotimes [i 48] (ffi/write hints :uint8 0 i))
    (ffi/write hints :int 1 O-ai-socktype)            ; SOCK_STREAM
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
      (dotimes [i 16] (ffi/write tv :uint8 0 i))
      (ffi/write tv :long secs 0)
      (c-setsockopt fd sol so tv 16)
      (finally (ffi/free tv)))))

(def interrupt-slice-ms
  "How long one `recv` waits for the socket to become readable before checking
  whether its thread has been interrupted. jolt's Thread.interrupt sets the
  flag and does not reach a thread inside a blocking syscall (measured: a
  parked recv ran its whole SO_RCVTIMEO after an interrupt), so `recv` waits
  in slices of this length and checks the flag between them, throwing
  InterruptedException — the exception an interrupted sleep throws. A caller
  that cancels an eval gets its thread back within one slice rather than one
  receive timeout; the receive timeout stays the bound on the read as a whole."
  250)

(def ^:private eintr 4)

(defn- await-readable!
  "Park until `fd` has something to read (or has hung up), in
  `interrupt-slice-ms` slices: :ready, or :timeout once `timeout-ms` (nil for
  none) has elapsed with nothing to read. Throws InterruptedException if the
  thread was interrupted between slices."
  [fd timeout-ms]
  (let [deadline (when (and timeout-ms (pos? timeout-ms)) (+ (System/currentTimeMillis) timeout-ms))
        pf (ffi/alloc 8)]
    (try
      ;; struct pollfd { int fd; short events; short revents; }: POLLIN (1) in
      ;; the low half of the int at offset 4, revents zeroed in the high half.
      (dotimes [i 8] (ffi/write pf :uint8 0 i))
      (ffi/write pf :int fd 0)
      (ffi/write pf :int 1 4)
      (loop []
        (when (Thread/interrupted)
          (throw (jolt.host/throwable "java.lang.InterruptedException" "recv interrupted")))
        (let [now (System/currentTimeMillis)
              slice (if deadline (min interrupt-slice-ms (max 0 (- deadline now))) interrupt-slice-ms)
              pr (c-poll pf 1 (int slice))]
          (cond
            (pos? pr) :ready
            (and deadline (>= (System/currentTimeMillis) deadline)) :timeout
            (zero? pr) (recur)
            (= (poller/errno) eintr) (recur)
            :else :ready)))
      (finally (ffi/free pf)))))

(defn connect
  "Open a connection to an nREPL server. Returns a transport (an opaque map).
  `:recv-timeout-secs` bounds every read: a server that stops replying turns
  into a nil message (connection treated as closed) instead of a caller
  blocked in recv forever. Reads are interruptible: see `interrupt-slice-ms`."
  ([host port] (connect host port nil))
  ([host port {:keys [recv-timeout-secs]}]
   (let [fd (raw-connect host port)]
     (when recv-timeout-secs (set-recv-timeout! fd recv-timeout-secs))
     {:fd fd :buf (atom "") :lock (Object.)
      :recv-timeout-ms (when recv-timeout-secs (* 1000 recv-timeout-secs))})))

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
  the connection closes or the receive timeout elapses (then nil). Throws
  InterruptedException when the reading thread is interrupted, within
  `interrupt-slice-ms`."
  [{:keys [fd buf recv-timeout-ms]}]
  (loop []
    (let [r (bencode/decode @buf 0)]
      (if r
        (do (swap! buf subs (second r)) (first r))
        (when (= :ready (await-readable! fd recv-timeout-ms))
          (let [b (ffi/alloc bufsize)
                chunk (try (let [k (c-recv fd b bufsize 0)]
                             (when (pos? k) (String. (ffi/read-array b k) "ISO-8859-1")))
                           (finally (ffi/free b)))]
            (when chunk (swap! buf str chunk) (recur))))))))

(defn close [{:keys [fd]}] (c-close fd) nil)
