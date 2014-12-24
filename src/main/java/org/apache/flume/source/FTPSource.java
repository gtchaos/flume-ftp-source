/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.flume.source;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.SimpleEvent;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.io.IOException;


import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Iterator;


import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;


/*
 * @author Luis Lazaro // lalazaro@keedio.com
    KEEDIO
 */
public class FTPSource extends AbstractSource implements Configurable, PollableSource {
    
    private static final Logger log = LoggerFactory.getLogger(FTPSource.class);
    private HashMap<String, Long> sizeFileList = new HashMap<>();
    private HashSet<String> existFileList = new HashSet<>();
    private final int CHUNKSIZE = 1024;   //event size in bytes
    private FTPSourceUtils ftpSourceUtils;
    private long eventCount = 0;
    
    @Override
    public void configure(Context context) {            
       ftpSourceUtils = new FTPSourceUtils(context);
       ftpSourceUtils.connectToserver();
       try {
            sizeFileList = loadMap("hasmap.ser");
       } catch(IOException | ClassNotFoundException e) {
            e.printStackTrace();
       }
    }
    
    /*
    @enum Status , process source configured from context
    */
    public PollableSource.Status process() throws EventDeliveryException {
       
       try {
           log.info("data processed: " + eventCount/1024  + " MBytes" + " actual dir " + ftpSourceUtils.getFtpClient().printWorkingDirectory() + " files : " +
               sizeFileList.size());
           //String dirToList = "/home/mortadelo/ftp";
           discoverElements(ftpSourceUtils.getFtpClient(),ftpSourceUtils.getFtpClient().printWorkingDirectory(), "", 0);           
       } catch(IOException e){
           e.printStackTrace();
       }
       cleanList(sizeFileList);
       existFileList.clear();
       saveMap(sizeFileList);
       
        try 
        {  
            Thread.sleep(10000);				
            return PollableSource.Status.READY;     //source was successfully able to generate events
        } catch(InterruptedException inte){
            inte.printStackTrace();
            return PollableSource.Status.BACKOFF;   //inform the runner thread to back off for a bit		
        }
    }

 
    public void start(Context context) {
        log.info("Starting sql source {} ...", getName());
        super.start();	    
    }
    

    @Override
    public void stop() {
        saveMap(sizeFileList);
            try {
                if (ftpSourceUtils.getFtpClient().isConnected()) {
                    ftpSourceUtils.getFtpClient().logout();
                    ftpSourceUtils.getFtpClient().disconnect();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            super.stop();
    }
    
    
    /*
    @void process last append to files
    */
    public void processMessage(byte[] lastInfo){
        eventCount++;
        byte[] message = lastInfo;
        Event event = new SimpleEvent();
        Map<String, String> headers =  new HashMap<String, String>();  
        headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
        event.setBody(message);
        event.setHeaders(headers);
        getChannelProcessor().processEvent(event);
    }
    
    
    
    public void discoverElements(FTPClient ftpClient, String parentDir, String currentDir, int level) throws IOException {
        
        String dirToList = parentDir;
        if (!currentDir.equals("")) {
            dirToList += "/" + currentDir;
        }
        FTPFile[] subFiles = ftpClient.listFiles(dirToList);
        if (subFiles != null && subFiles.length > 0) {
            
            for (FTPFile aFile : subFiles) {
                String currentFileName = aFile.getName();
                if (currentFileName.equals(".") || currentFileName.equals("..")) {
                    System.out.println("skip parent directory and directory itself");
                    continue;
                }
                
                if (aFile.isDirectory()) {
                    System.out.println("[" + aFile.getName() + "]");
                    ftpClient.changeWorkingDirectory(parentDir);
                    discoverElements(ftpClient, dirToList, aFile.getName(), level + 1);
                    continue;
                } else if (aFile.isFile()) { //aFile is a regular file
                    ftpClient.changeWorkingDirectory(dirToList);
                    this.existFileList.add(aFile.getName());
                    if (!(sizeFileList.containsKey(aFile.getName()))){ //new file
                        sizeFileList.put(aFile.getName(), aFile.getSize());
                        log.info("discovered: " + aFile.getName() + "," + " ," + sizeFileList.size() + " , Actual "  + aFile.getSize());
                        final InputStream inputStream = ftpClient.retrieveFileStream(aFile.getName());
                        Thread threadNewFile = new Thread( new Runnable(){
                                    @Override
                                    public void run(){
                                        try {
                                            
                                            byte[] bytesArray = new byte[CHUNKSIZE];
                                            while ((inputStream.read(bytesArray)) > 0) {
                                                processMessage(bytesArray);
                                            }
                                            inputStream.close();
                                           
                                        } catch(IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                });
                                    threadNewFile.setName("hiloNewFile_" + aFile.getName());
                                    threadNewFile.start();
                        //inputStream.close();
                        boolean success = ftpClient.completePendingCommand();
                        ftpClient.changeWorkingDirectory(dirToList);
                        continue;
                    } else  { //known file                        
                        long dif = aFile.getSize() - sizeFileList.get(aFile.getName());
                        if (dif > 0 ){ //known and modified
                            final InputStream inputStream = ftpClient.retrieveFileStream(aFile.getName());
                            final long prevSize = sizeFileList.get(aFile.getName());
                            sizeFileList.put(aFile.getName(), aFile.getSize()); //save new size
                            log.info("modified: " + aFile.getName() + " , dif " + dif + " ," + sizeFileList.size() + " , new size "  + aFile.getSize());
                            Thread threadOldFile = new Thread( new Runnable(){
                                    @Override
                                    public void run(){
                                        try {
                                            inputStream.skip(prevSize);
                                            byte[] bytesArray = new byte[CHUNKSIZE];
                                            while ((inputStream.read(bytesArray)) > 0) {
                                                processMessage(bytesArray);
                                            }
                                            inputStream.close();
                                           
                                        } catch(IOException e) {
                                            e.printStackTrace();                                            
                                        } 
                                    }
                                });
                                    threadOldFile.setName("hiloOldFile_" + aFile.getName());
                                    threadOldFile.start();
                            boolean success = ftpClient.completePendingCommand(); //wlways
                            continue;
                        }
                        //System.out.println(dirToList);
                        ftpClient.changeWorkingDirectory(parentDir);
                       continue;
                    }
                } else if (aFile.isSymbolicLink()) {
                    log.info(aFile.getName() + " is a link of " + aFile.getLink() + " access denied" );
                    ftpClient.changeWorkingDirectory(parentDir);
                    continue;
                } else if (aFile.isUnknown()) {
                    log.info(aFile.getName() + " unknown type of file" );
                    ftpClient.changeWorkingDirectory(parentDir);
                    continue;
                } else {
                    ftpClient.changeWorkingDirectory(parentDir);
                    continue;
                }
                
            } //fin de bucle
        } //el listado no es vacío
    }//fin de método
    
    
   /*
    @void Serialize hashmap
    */
    public void saveMap(HashMap<String, Long> map){
        try { 
            FileOutputStream fileOut = new FileOutputStream("hasmap.ser");
            ObjectOutputStream out = new ObjectOutputStream(fileOut);
            out.writeObject(map);
            out.close();
        } catch(FileNotFoundException e){
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }
    }
    
    /*
    @return HashMap<String,Long> objects
    */
    public HashMap<String,Long> loadMap(String name) throws ClassNotFoundException, IOException{
        FileInputStream map = new FileInputStream(name);
        ObjectInputStream in = new ObjectInputStream(map);
        HashMap hasMap = (HashMap)in.readObject();
        in.close();
        return hasMap;
    } 
    
    
 
   
   /*
    @void, delete file from hashmaps if deleted from ftp
    */
    public void cleanList(HashMap<String,Long> map) {
          for (Iterator<String> iter=map.keySet().iterator();iter.hasNext();) {
          final String fileName = iter.next();
          if (!(existFileList.contains(fileName))){ 
              iter.remove();
          }
        }
    }
   
    
    
} //end of class
