AkkaStreamsCommandPipe
======================
Akka Streams flow which pipes data through a shell command

Warning
=======
The process that is run may have no notion of backpressure. For instance, testing with /bin/cat it will
buffer all input and wait for the inputStream to be closed before sending any output.

TODO: 
 - How to handle sterr output?
 - Check exit status of process?
 - Handle generic ByteStrings as output instead of Strings