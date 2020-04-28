
package com.ebook.common.view;

import android.graphics.Color;

import com.blankj.utilcode.util.SPUtils;
import com.ebook.common.R;
import com.ebook.common.util.DisplayUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReadBookControl {
    public static final int DEFAULT_TEXT = 2;
    public static final int DEFAULT_BG = 1;

    private static List<Map<String,Integer>> textKind;
    private static List<Map<String,Integer>> textDrawable;

    private int textSize;
    private int textExtra;
    private int textColor;
    private int textBackground;

    private int textKindIndex = DEFAULT_TEXT;
    private int textDrawableIndex = DEFAULT_BG;

    private Boolean canClickTurn = true;
    private Boolean canKeyTurn = true;

    private SPUtils preference;

    private static ReadBookControl readBookControl;

    public static ReadBookControl getInstance(){
        if(readBookControl == null){
            synchronized (ReadBookControl.class){
                if(readBookControl == null){
                    readBookControl = new ReadBookControl();
                }
            }
        }
        return readBookControl;
    }
    private ReadBookControl(){
        if(null == textKind){
            textKind = new ArrayList<>();
            Map<String,Integer> temp1 = new HashMap<>();
            temp1.put("textSize", 14);
            temp1.put("textExtra", DisplayUtil.dip2px(6.5f));
            textKind.add(temp1);

            Map<String,Integer> temp2 = new HashMap<>();
            temp2.put("textSize", 16);
            temp2.put("textExtra", DisplayUtil.dip2px(8));
            textKind.add(temp2);

            Map<String,Integer> temp3 = new HashMap<>();
            temp3.put("textSize", 17);
            temp3.put("textExtra", DisplayUtil.dip2px(9));
            textKind.add(temp3);

            Map<String,Integer> temp4 = new HashMap<>();
            temp4.put("textSize", 20);
            temp4.put("textExtra", DisplayUtil.dip2px(11));
            textKind.add(temp4);

            Map<String,Integer> temp5 = new HashMap<>();
            temp5.put("textSize", 22);
            temp5.put("textExtra", DisplayUtil.dip2px(13));
            textKind.add(temp5);
        }
        if(null == textDrawable){
            textDrawable = new ArrayList<>();
            Map<String,Integer> temp1 = new HashMap<>();
            temp1.put("textColor",Color.parseColor("#3E3D3B"));
            temp1.put("textBackground", R.drawable.shape_bg_readbook_white);
            textDrawable.add(temp1);

            Map<String,Integer> temp2 = new HashMap<>();
            temp2.put("textColor",Color.parseColor("#5E432E"));
            temp2.put("textBackground", R.drawable.bg_readbook_yellow);
            textDrawable.add(temp2);

            Map<String,Integer> temp3 = new HashMap<>();
            temp3.put("textColor",Color.parseColor("#22482C"));
            temp3.put("textBackground",R.drawable.bg_readbook_green);
            textDrawable.add(temp3);

            Map<String,Integer> temp4 = new HashMap<>();
            temp4.put("textColor",Color.parseColor("#808080"));
            temp4.put("textBackground",R.drawable.bg_readbook_black);
            textDrawable.add(temp4);
        }
        preference = SPUtils.getInstance("CONFIG");
        this.textKindIndex = preference.getInt("textKindIndex",DEFAULT_TEXT);
        this.textSize = textKind.get(textKindIndex).get("textSize");
        this.textExtra = textKind.get(textKindIndex).get("textExtra");
        this.textDrawableIndex = preference.getInt("textDrawableIndex",DEFAULT_BG);
        this.textColor = textDrawable.get(textDrawableIndex).get("textColor");
        this.textBackground = textDrawable.get(textDrawableIndex).get("textBackground");

        this.canClickTurn = preference.getBoolean("canClickTurn",true);
        this.canKeyTurn = preference.getBoolean("canClickTurn",true);
    }

    public int getTextSize() {
        return textSize;
    }

    public int getTextExtra() {
        return textExtra;
    }

    public int getTextColor() {
        return textColor;
    }

    public int getTextBackground() {
        return textBackground;
    }

    public int getTextKindIndex() {
        return textKindIndex;
    }

    public void setTextKindIndex(int textKindIndex) {
        this.textKindIndex = textKindIndex;
        preference.put("textKindIndex",textKindIndex);
        this.textSize = textKind.get(textKindIndex).get("textSize");
        this.textExtra = textKind.get(textKindIndex).get("textExtra");
    }

    public int getTextDrawableIndex() {
        return textDrawableIndex;
    }

    public void setTextDrawableIndex(int textDrawableIndex) {
        this.textDrawableIndex = textDrawableIndex;
        preference.put("textDrawableIndex",textDrawableIndex);
        this.textColor = textDrawable.get(textDrawableIndex).get("textColor");
        this.textBackground = textDrawable.get(textDrawableIndex).get("textBackground");
    }

    public static List<Map<String, Integer>> getTextKind() {
        return textKind;
    }

    public static List<Map<String, Integer>> getTextDrawable() {
        return textDrawable;
    }

    public Boolean getCanKeyTurn() {
        return canKeyTurn;
    }

    public void setCanKeyTurn(Boolean canKeyTurn) {
        this.canKeyTurn = canKeyTurn;
       preference.put("canKeyTurn",canKeyTurn);
    }

    public Boolean getCanClickTurn() {
        return canClickTurn;
    }

    public void setCanClickTurn(Boolean canClickTurn) {
        this.canClickTurn = canClickTurn;
        preference.put("canClickTurn",canClickTurn);
    }
}