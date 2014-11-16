package com.maximgalushka.classifier.twitter.service;

import org.apache.log4j.Logger;

import java.net.ServerSocket;
import java.net.Socket;

/**
 * @since 9/11/2014.
 */
public class StopServiceHandler implements Runnable {

    public static final Logger log = Logger.getLogger(StopServiceHandler.class);

    @Override
    public void run() {
        //send kill signal to running instance, if any
        try {
            new Socket("localhost", 4000).getInputStream().read(); //block until its done
        } catch (Exception e) { //if no one is listening, we're the only instance
            log.error(e);
            e.printStackTrace();
        }
        //start kill listener for self
        new Thread() {
            @Override
            public void run() {
                try {
                    ServerSocket serverSocket = new ServerSocket(4000);
                    serverSocket.accept();

                    //do cleanup here

                    serverSocket.close();
                } catch (Exception e) {
                    log.error(e);
                    e.printStackTrace();
                }
                // TODO: make it more gracefull
                // TODO: create flag here TwitterStreamProcessor:68 to complete last loop
                System.exit(0);
            }
        }.start();
    }
}
