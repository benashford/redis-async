Development for an asynchronous Redis client for Clojure.  Currently under development, not yet ready for use:

### Documentation, TBC.

### Still to-do

1. Utility readers for idiomatic reading.
2. Use core async channels when writing.
3. Documentation.
4. Scripting support.
5. Handling errors from Redis.
6. The 'monitor' command.
7. Pub/sub commands.
8. Transaction commands.
9. Test coverage.
10. Multiple-connections per client.
11. Testing/handling bad/failed connections.
13. Ensure that closed connections remove themselves from the connection pool. (Or ensure that closure via the connection-pool is the only way to do it.)
1. Auto-generate client functions from commands.json
