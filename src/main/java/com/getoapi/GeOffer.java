package com.getoapi;

import java.util.Date;

public class GeOffer {
    public int id;
    public int userId;
    public int itemId;
    public int price;
    public int quantitySold;
    public int spent;
    public int totalQuantity;
    public int state;
    public int slot;
    public Date updated;

    public GeOffer(int id, int userId, int itemId, int price, int quantitySold, int spent, int totalQuantity, int state, int slot, Date updated){}
}
