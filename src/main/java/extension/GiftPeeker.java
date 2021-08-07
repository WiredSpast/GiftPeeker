package extension;

import gearth.extensions.Extension;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.extra.tools.PacketInfoSupport;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HStuff;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.protocol.connection.HClient;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

@ExtensionInfo(
        Title = "GiftPeeker",
        Description = "Find out what is inside a gift",
        Version = "0.2",
        Author = "WiredSpast"
)
public class GiftPeeker extends Extension {
    private PacketInfoSupport packetInfoSupport;
    private final Map<String, JSONObject> productData = new HashMap<>();
    private final Object productDataLock = new Object();

    private final HashMap<Integer, HFloorItem> floorItems = new HashMap<>();
    private final Object floorItemsLock = new Object();
    private boolean peeking = false;

    public GiftPeeker(String[] args) {
        super(args);
    }

    public static void main(String[] args) {
        new GiftPeeker(args).run();
    }

    @Override
    protected void initExtension() {
        packetInfoSupport = new PacketInfoSupport(this);

        this.onConnect(this::doOnConnect);

        // Chat
        packetInfoSupport.intercept(HMessage.Direction.TOSERVER, "Chat", this::onChatSend);

        // FloorItems
        packetInfoSupport.intercept(HMessage.Direction.TOCLIENT, "Objects", this::onObjects);
        packetInfoSupport.intercept(HMessage.Direction.TOCLIENT, "ObjectAdd", this::onObjectAddOrUpdate);
        packetInfoSupport.intercept(HMessage.Direction.TOCLIENT, "ObjectUpdate", this::onObjectAddOrUpdate);
        packetInfoSupport.intercept(HMessage.Direction.TOCLIENT, "ObjectRemove", this::onObjectRemove);
        packetInfoSupport.intercept(HMessage.Direction.TOCLIENT, "ObjectDataUpdate", this::onObjectDataUpdate);
    }

    private void doOnConnect(String host, int i, String s1, String s2, HClient hClient) {
        new Thread(() -> {
            synchronized (productDataLock) {
                try {
                    JSONObject productDataJson = new JSONObject(IOUtils.toString(new URL(getProductDataUrl(host)).openStream(), StandardCharsets.UTF_8));
                    System.out.println(productDataJson.toString(3));
                    productDataJson.getJSONObject("productdata").getJSONArray("product").forEach(o -> {
                        JSONObject productJson = (JSONObject) o;
                            productData.put("" + productJson.get("code"), productJson);
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private String getProductDataUrl(String host) {
        switch(host) {
            case "game-nl.habbo.com":
                return "https://www.habbo.nl/gamedata/productdata_json/1";
            case "game-br.habbo.com":
                return "https://www.habbo.com.br/gamedata/productdata_json/1";
            case "game-tr.habbo.com":
                return "https://www.habbo.com.tr/gamedata/productdata_json/1";
            case "game-de.habbo.com":
                return "https://www.habbo.de/gamedata/productdata_json/1";
            case "game-fr.habbo.com":
                return "https://www.habbo.fr/gamedata/productdata_json/1";
            case "game-fi.habbo.com":
                return "https://www.habbo.fi/gamedata/productdata_json/1";
            case "game-es.habbo.com":
                return "https://www.habbo.es/gamedata/productdata_json/1";
            case "game-it.habbo.com":
                return "https://www.habboit/gamedata/productdata_json/1";
            case "game-s2.habbo.com":
                return "https://sandbox.habbo.com/gamedata/productdata_json/1";
            default:
                return "https://www.habbo.com/gamedata/productdata_json/1";
        }
    }

    private void onChatSend(HMessage hMessage) {
        String msg = hMessage.getPacket().readString();
        if(msg.startsWith(":peek")) {
            hMessage.setBlocked(true);
            peeking = !peeking;
            packetInfoSupport.sendToClient("Shout", -1, "GiftPeeker " + (peeking ? "en" : "dis") + "abled!", 0, 30, 0, -1);

            updateAllGifts();
        }
    }

    private void onObjects(HMessage hMessage) {
        synchronized (floorItemsLock) {
            floorItems.clear();
            Arrays.asList(HFloorItem.parse(hMessage.getPacket()))
                    .forEach(hFloorItem -> floorItems.put(hFloorItem.getId(), hFloorItem));
        }

        peeking = false;
    }

    private void onObjectAddOrUpdate(HMessage hMessage) {
        HFloorItem hFloorItem = new HFloorItem(hMessage.getPacket());
        synchronized (floorItemsLock) {
            floorItems.put(hFloorItem.getId(), hFloorItem);

            if(peeking) {
                updateGift(hFloorItem);
            }
        }
    }

    private void onObjectRemove(HMessage hMessage) {
        synchronized (floorItemsLock) {
            this.floorItems.remove(Integer.parseInt(hMessage.getPacket().readString()));
        }
    }

    private void onObjectDataUpdate(HMessage hMessage) {
        HPacket packet = hMessage.getPacket();
        synchronized (floorItemsLock) {
            HFloorItem item = floorItems.get(Integer.parseInt(packet.readString()));
            if (item != null) {
                item.setCategory(packet.readInteger());
                item.setStuff(HStuff.readData(packet, item.getCategory()));

                if(peeking) {
                    updateGift(item);
                }
            }
        }
    }

    private void updateAllGifts() {
        synchronized (floorItemsLock) {
            System.out.println(floorItems.size());
            floorItems.values().forEach(this::updateGift);
        }
    }

    private void updateGift(HFloorItem item) {
        if(item.getCategory() == 1) {
            if(Arrays.asList(item.getStuff()).contains("PRODUCT_CODE") && Arrays.asList(item.getStuff()).contains("MESSAGE")) {
                Object[] updatedStuff = setGiftInfo(item.getStuff());
                if (!Arrays.equals(item.getStuff(), updatedStuff)) {
                    Object[] stuff = item.getStuff();
                    if (peeking) {
                        stuff = updatedStuff;
                    }

                    int headerId = this.getPacketInfoManager().getAllPacketInfoFromName(HMessage.Direction.TOCLIENT, "ObjectDataUpdate").get(0).getHeaderId();

                    HPacket packet = new HPacket(headerId);
                    packet.appendString(Integer.toString(item.getId()));
                    packet.appendInt(item.getCategory());
                    packet.appendInt((Integer) stuff[0]);
                    for(int i = 1; i < stuff.length; i++) {
                        packet.appendString((String) stuff[i], StandardCharsets.UTF_8);
                    }

                    this.sendToClient(packet);
                }
            }
        }
    }

    private Object[] setGiftInfo(Object[] stuff) {
        List<Object> stuffList = Arrays.asList(stuff);
        Object[] newStuff = Arrays.copyOf(stuff, stuff.length);
        if(stuffList.contains("PRODUCT_CODE") && stuffList.contains("MESSAGE")) {
            int messageIndex = stuffList.indexOf("MESSAGE") + 1;
            int productCodeIndex = stuffList.indexOf("PRODUCT_CODE") + 1;
            synchronized (productDataLock) {
                JSONObject data = productData.get((String) stuffList.get(productCodeIndex));
                newStuff[messageIndex] = "Product code: " + data.getString("code") + "\r"
                        + "Name: " + data.getString("name") + "\r"
                        + "Description: " + data.getString("description");
            }
        }
        return newStuff;
    }
}
