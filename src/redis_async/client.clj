(ns redis-async.client
  (:refer-clojure :exclude [time sync keys sort type get set])
  (:require [clojure.string :as s]
            [redis-async.core :refer :all]))

(defmacro defredis [fn-n]
  (let [cmd (as-> fn-n x
                  (name x)
                  (s/split x #"-")
                  (mapv s/upper-case x))]
    `(defn ~fn-n [& ~'params]
       (let [redis#  (when-not *pipe* (first ~'params))
             params# (if *pipe* ~'params (drop 1 ~'params))]
         (apply send-cmd redis# ~cmd params#)))))

;; Commands

(def commands
  {:keys ['del
          'dump
          'exists
          'expire
          'expireat
          'keys
          'migrate
          'move
          'object
          'persist
          'pexpire
          'pexpireat
          'pttl
          'randomkey
          'rename
          'renamenx
          'restore
          'sort
          'ttl
          'type
          'scan]
   :strings ['append
             'bitcount
             'bitop
             'bitpos
             'decr
             'decrby
             'get
             'getbit
             'getrange
             'getset
             'incr
             'incrby
             'incrbyfloat
             'mget
             'mset
             'msetnx
             'psetex
             'set
             'setbit
             'setex
             'setnx
             'setrange
             'strlen]
   :hashes ['hdel
            'hexists
            'hget
            'hgetall
            'hincrby
            'hincrbyfloat
            'hkeys
            'hlen
            'hmget
            'hmset
            'hset
            'hsetnx
            'hvals
            'hscan]
   :lists ['blpop
           'brpop
           'brpoplpush
           'lindex
           'linsert
           'llen
           'lpop
           'lpush
           'lpushx
           'lrange
           'lrem
           'lset
           'ltrim
           'rpop
           'rpoplpush
           'rpush
           'rpushx]
   :sets ['sadd
          'scard
          'sdiff
          'sdiffstore
          'sinter
          'sinterstore
          'sismember
          'smembers
          'smove
          'spop
          'srandmember
          'srem
          'sunion
          'sunionstore
          'sscan]
   :sorted-sets ['zadd
                 'zcard
                 'zcount
                 'zincrby
                 'zinterstore
                 'zlexcount
                 'zrange
                 'zrangebylex
                 'zrevrangebylex
                 'zrangebyscore
                 'zrank
                 'zrem
                 'zremrangebylex
                 'zremrangebyrank
                 'zremrangebyscore
                 'zrevrange
                 'zrevrangebyscore
                 'zrevrank
                 'zscore
                 'zunionstore
                 'zscan]
   :connection ['auth 'echo 'ping 'quit 'select]
   :server ['bgrewriteaof
            'bgsave
            'client-kill
            'client-list
            'client-getname
            'client-pause
            'client-setname
            'cluster-slots
            'command
            'command-count
            'command-getkeys
            'command-info
            'config-get
            'config-rewrite
            'config-set
            'config-resetstat
            'dbsize
            'debug-object
            'debug-segfault
            'flushall
            'flushdb
            'info
            'lastsave
                                        ;'monitor
            'role
            'save
            'shutdown
            'slaveof
            'slowlog
            'sync
            'time]})

(doseq [cmd (flatten (clojure.core/vals commands))]
  (eval `(defredis ~cmd)))
