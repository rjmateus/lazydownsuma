Generate a web application that allows to download files from this we application server server.
The web application must use the spark framework from: https://github.com/perwendel/spark
It should have just one endpoint to download files from.

The files can be stored in the a server folder, which is it's cache.
If the server have the file cached locally it server the file directly.
If the file is not present in the cache, it must download it from upstream, and store in the cache.

The application can receive multiple requests for the same file at the same time from different clients. In those cases, it must only download the file from upstream once.
File checksum must be verified.
Analyze two options in regard the clients receiving the file in this case. They can wait for the full download before the server start sending the file to the client. Another option is start streming it to the clients as soon it starts receiving the file content from upstream. Analyze the best option.

To implement this logic, verify if a good opensource java library exists

It must be developed in Java, and using maven.
The upstream site can be configure in a java constant, for example.