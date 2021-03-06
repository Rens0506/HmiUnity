package hmi.unityembodiments;

import static nl.utwente.hmi.middleware.helpers.JsonNodeBuilders.array;
import static nl.utwente.hmi.middleware.helpers.JsonNodeBuilders.object;


import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;

import hmi.animation.VJoint;
import hmi.animationembodiments.SkeletonEmbodiment;
import hmi.environment.bodyandfaceembodiments.BodyAndFaceEmbodiment;
import hmi.environmentbase.CopyEnvironment;
import hmi.faceanimation.FaceController;
import hmi.faceanimation.model.MPEG4Configuration;
import hmi.faceembodiments.AUConfig;
import hmi.faceembodiments.FACSFaceEmbodiment;
import hmi.faceembodiments.FaceEmbodiment;
import hmi.worldobjectenvironment.VJointWorldObject;
import hmi.worldobjectenvironment.WorldObject;
import hmi.worldobjectenvironment.WorldObjectEnvironment;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;

import nl.utwente.hmi.middleware.Middleware;
import nl.utwente.hmi.middleware.MiddlewareListener;
import nl.utwente.hmi.middleware.loader.GenericMiddlewareLoader;

@Slf4j
public class UnityEmbodiment implements MiddlewareListener, SkeletonEmbodiment, FaceEmbodiment, BodyAndFaceEmbodiment, FaceController, FACSFaceEmbodiment {
	
	private Middleware middleware;
	
	public static final byte MSG_TYPE_AGENT_SPEC = 0x01;
	public static final byte MSG_TYPE_AGENT_REQ = 0x02;
	public static final byte MSG_TYPE_AGENT_STATE = 0x03;
	
	public static final String JSON_MSG_BINARY = "binaryMessage";
    public static final String JSON_MSG_WORLDUPDATE = "worldUpdate";
    public static final String JSON_MSG_WORLDUPDATE_CONTENT = "objects";
	public static final String JSON_MSG_BINARY_CONTENT = "content";
	
	byte[] msgbuf;
	private VJoint animationRoot = null;
	private List<VJoint> jointList;
    private String vhId = "";
    private String loaderId = "";
    private boolean configured = false;

    private MPEG4Configuration currentConfig = new MPEG4Configuration();
    private CopyEnvironment ce = null;
    
    //long t0;
    //Connection connection;

    private LinkedHashMap<String,Float> faceMorphTargets;
    private LinkedBlockingQueue<WorldObjectUpdate> objectUpdates;

    private WorldObjectEnvironment woe;
    
    public UnityEmbodiment(String vhId, String loaderId, String specificMiddlewareLoader, Properties props, WorldObjectEnvironment woe, CopyEnvironment ce) {
		this.vhId = vhId;
		this.loaderId = loaderId;
		this.woe = woe;
		this.ce = ce;
    	msgbuf = new byte[32768]; // Buffer: ~100bones * (4bytes * (3pos + 4rot) + 255) = ~28300
    	objectUpdates = new LinkedBlockingQueue<WorldObjectUpdate>();
		        
        GenericMiddlewareLoader gml = new GenericMiddlewareLoader(specificMiddlewareLoader, props);
        middleware = gml.load();
        middleware.addListener(this);
        configured = false;
    }
    
    public boolean isConfigured() {
    	return configured;
    }
    
    public void RequestAgent(String id, String source) {
    	log.info("Req: "+id);
    	ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    	DataOutputStream out = new DataOutputStream(byteStream);
    	
    	try {
    		out.writeByte(MSG_TYPE_AGENT_REQ);
    		out.writeBytes(id);
    		out.writeByte((byte)0x00);
    		out.writeBytes(source);
    		out.writeByte((byte)0x00);
			out.flush();
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		JsonNode value = object(JSON_MSG_BINARY,
				object(JSON_MSG_BINARY_CONTENT, Base64.encode(byteStream.toByteArray()))
			).end();
		middleware.sendData(value);
		
    	try {
			out.close();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
    	log.info("Sent AgentSpecRequest to Middleware.");
    }
	
	String ParseString(ByteBuffer reader) {
		String res = "";
		while (true) {
			byte b = reader.get();
			if (b == 0x00) break;
			else res += (char) b;
		}
		return res;
	}


	@Override
	public synchronized void receiveData(JsonNode jn) {
		if (jn.has(JSON_MSG_BINARY)) {
			byte[] bytes = Base64.decode(jn.get(JSON_MSG_BINARY).get(JSON_MSG_BINARY_CONTENT).asText());
			ByteBuffer reader = ByteBuffer.wrap(bytes);
			reader.order(ByteOrder.LITTLE_ENDIAN);
			
			byte msgType = reader.get();
			if (msgType == MSG_TYPE_AGENT_SPEC) {
				ParseAgentSpec(reader);
			}
		} else if (jn.has(JSON_MSG_WORLDUPDATE)) {
		    JsonNode objects = jn.get(JSON_MSG_WORLDUPDATE).get(JSON_MSG_WORLDUPDATE_CONTENT);
		    Iterator<String> objectNames = objects.fieldNames();
		    while (objectNames.hasNext()) {
		        String objectName = objectNames.next();
                byte[] bytes = Base64.decode(objects.get(objectName).asText());
                float[] translation = ParseBinaryWorldObjectData(bytes);
                try
                {
                    objectUpdates.put(new WorldObjectUpdate(objectName, translation));
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
		    }
		}
	}
    
    float[] ParseBinaryWorldObjectData(byte[] bytes) {
        ByteBuffer reader = ByteBuffer.wrap(bytes);
        reader.order(ByteOrder.LITTLE_ENDIAN);
        float x = reader.getFloat();
        float y = reader.getFloat();
        float z = reader.getFloat();
        float qw = reader.getFloat();
        float qx = reader.getFloat();
        float qy = reader.getFloat();
        float qz = reader.getFloat();
        
        return new float[] { x, y, z };
    }
    
	void ParseAgentSpec(ByteBuffer reader) {
		faceMorphTargets = new LinkedHashMap<String,Float>();
		
		jointList = new ArrayList<VJoint>();
		log.info("reading agent spec");
		String id = ParseString(reader); // CHARS* SK_NAME
		log.info("ID: "+id);
		
		int skBones = reader.getInt(); // INT32  BONES#
		log.info("Bones: "+skBones);
		
		HashMap<String, VJoint> jointsLUT = new HashMap<String, VJoint>();
		log.info("Parsing skeleton %s, with %d bones\n", id, skBones);
		
		for (int b = 0; b < skBones; b++) {
			String bName = ParseString(reader);
			String pName = ParseString(reader);
            String hAnimName = ParseString(reader);
			
			float x = reader.getFloat();
			float y = reader.getFloat();
			float z = reader.getFloat();
			float qw = reader.getFloat();
			float qx = reader.getFloat();
			float qy = reader.getFloat();
			float qz = reader.getFloat();
			VJoint current = new VJoint();
			current.setName(bName);
			current.setSid(hAnimName);
			current.setId(bName); // could be prefixed by vhId to be "truly" unique?
			current.setTranslation(x, y, z);
			current.setRotation(qw, qx, qy, qz);
			
			if (pName.length() == 0) {
				animationRoot = current;
			} else {
				jointsLUT.get(pName).addChild(current);
			}
			
			jointList.add(current);
			jointsLUT.put(bName, current);
			log.info(String.format("    Bone %s, child of %s. HAnim: %s // [%.2f %.2f %.2f] [%.2f %.2f %.2f %.2f]", bName, pName, hAnimName, x,y,z,qw,qx,qy,qz));
		}
		

		int fTargets = reader.getInt(); // INT32  BONES#

		log.info("Face Targets: "+fTargets);
        //ce.addCopyEmbodiment(this);
		
		for (int f = 0; f < fTargets; f++) {
			String cName = ParseString(reader);
			faceMorphTargets.put(cName, 0.0f);
			log.info(String.format("    Face Target: %s", cName));
		}
		configured = true;
	}
	
	public VJoint getAnimationVJoint() {
        return animationRoot;
    }
	
	@Override
	public synchronized void copy() {
		if (!configured) return;
	    log.info("copying ");
		while (!objectUpdates.isEmpty()) {
	        // TODO: Is poll() better than take() here? If take() blocks the copy() 
	        //   until we receive an object update, that might really affect framerate, no?
	        WorldObjectUpdate u = objectUpdates.poll();
	        WorldObject o = woe.getWorldObjectManager().getWorldObject(u.id); 
	        if (o == null) {
	            VJoint newJoint = new VJoint();
	            newJoint.setTranslation(u.data);
	            woe.getWorldObjectManager().addWorldObject(u.id, new VJointWorldObject(newJoint));
	        } else {
	            o.setTranslation(u.data);
	        }
	    }
	    
		ByteBuffer out = ByteBuffer.wrap(msgbuf);
		out.order(ByteOrder.LITTLE_ENDIAN);
		out.rewind();
		out.put(MSG_TYPE_AGENT_STATE);
    	out.put(vhId.getBytes(StandardCharsets.US_ASCII));
    	out.put((byte)0x00);
    	
    	out.putInt(jointList.size());
    	for (int j = 0; j < jointList.size(); j++) {
			VJoint cur = jointList.get(j);
    		float[] translation = new float[3];
    		float[] rotation = new float[4];
			cur.getTranslation(translation);
			cur.getRotation(rotation);
			out.putFloat(translation[0]);
			out.putFloat(translation[1]);
			out.putFloat(translation[2]);
			out.putFloat(rotation[0]);
			out.putFloat(rotation[1]);
			out.putFloat(rotation[2]);
			out.putFloat(rotation[3]);
    	}
    	
    	if (faceMorphTargets == null) out.putInt(0);
    	else {
    		out.putInt(faceMorphTargets.size());
    		for (Map.Entry<String,Float> entry : faceMorphTargets.entrySet()) {
        		out.putFloat(entry.getValue());
            }
    	}
		//DefaultMessage msg = new DefaultMessage(Arrays.copyOf(out.array(), out.position()));
		//msg.setProperty("content-length", ""+out.position());
    	
		JsonNode value = object(JSON_MSG_BINARY,
				object(JSON_MSG_BINARY_CONTENT, Base64.encode(Arrays.copyOf(out.array(), out.position())))
			).end();
		middleware.sendData(value);
		log.info("copied ");
    	//connection.send(msg, "/topic/UnityAgentControl");
	}
	

	// FaceEmbodiment
	@Override
	public FaceController getFaceController() {
		return this;
	}
	
	@Override
	public void setMorphTargets(String[] targetNames, float[] weights) {
		for (int i = 0; i < targetNames.length; i++) {
			faceMorphTargets.replace(targetNames[i], weights[i]);
		}
	}


	@Override
	public float getCurrentWeight(String targetName) {
		log.info("getCurrentWeight "+targetName);
		//TODO: unimplemented for now because never called?
		return 0.0f;
	}


	@Override
	public void addMorphTargets(String[] targetNames, float[] weights) {
		//TODO: unimplemented for now because never called?
		log.info("addMorphTargets");
		for (int i = 0; i < targetNames.length; i++) {
			log.info(targetNames[i]+" "+weights[i]);
		}
	}


	@Override
	public void removeMorphTargets(String[] targetNames, float[] weights) {
		//TODO: unimplemented for now because never called?
		log.info("removeMorphTargets");
		for (int i = 0; i < targetNames.length; i++) {
			log.info(targetNames[i]+" "+weights[i]);
		}
	}


	@Override
	public Collection<String> getPossibleFaceMorphTargetNames() {
		return faceMorphTargets.keySet();
	}
	/////////////////



	// FaceController
	@Override
	public void setMPEG4Configuration(MPEG4Configuration config) {
        currentConfig.setValues(Arrays.copyOf(config.getValues(),config.getValues().length));
	}


	@Override
	public void addMPEG4Configuration(MPEG4Configuration config) {
		log.info("addMPEG4Configuration");
        currentConfig.addValues(config);
	}


	@Override
	public void removeMPEG4Configuration(MPEG4Configuration config) {
		log.info("removeMPEG4Configuration");
        currentConfig.removeValues(config);
		
	}
	/////////////////

	@Override
	public void setAUs(AUConfig... configs) {
		// TODO Auto-generated method stub
		//use converter to translate to morph target set, then set morph targets
	}

	@Override
	public String getId() {
		return loaderId;
	}
	
}
