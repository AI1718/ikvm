/*
  Copyright (C) 2009 Volker Berlin (i-net software)

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the authors be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.

  Jeroen Frijters
  jeroen@frijters.net
  
*/
package junit.ikvm;

import java.io.*;
import java.util.HashMap;

import junit.framework.Assert;
import static junit.framework.Assert.fail;


/**
 * @author Volker Berlin
 */
public class ReferenceData{
    
    private static final boolean IKVM = System.getProperty("java.vm.name").equals("IKVM.NET");
    
    private static final String NO_DATA_MSG = " Please run the test first with a Sun Java VM to create reference data for your system.";

    private final HashMap<String, Object> data;
    private final File file;
    
    public ReferenceData(Class<? extends Object> clazz) throws Exception{
        String name = clazz.getName();
        String path = "references/" + name.replace('.', '/');
        file = new File(path).getAbsoluteFile();
        if(IKVM){
            if(!file.exists()){
                fail(NO_DATA_MSG);
            }
            FileInputStream fis = new FileInputStream(file);
            ObjectInputStream ois = new ObjectInputStream(fis);
            data = (HashMap<String, Object>)ois.readObject();
            fis.close();
        }else{
            data = new HashMap<String, Object>();
        }
    }
    
    public void save() throws Exception{
        if(!IKVM){
            file.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(data);
            oos.flush();
            oos.close();
        }
    }
    
    public boolean isIkvm(){
        return IKVM;
    }

    public void assertEquals(String key, Serializable value){
        if(key == null){
            fail("Key is null.");
        }
        if(IKVM){
            Object expected = data.get(key);
            if(expected == null && !data.containsKey(key)){
                fail("No Reference value for key:" + key + NO_DATA_MSG);
            }
            Assert.assertEquals(key, expected, value);
        }else{
            data.put(key, value);
        }
    }
}
