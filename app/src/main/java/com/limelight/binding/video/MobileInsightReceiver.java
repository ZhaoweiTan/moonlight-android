package com.limelight.binding.video;

/**
 * Created by yuanjieli on 8/17/17.
 */


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


public class MobileInsightReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {

        if(intent.getAction().equals("android.appwidget.action.APPWIDGET_ENABLED")) {
            //TODO: ???
        }
        else if(intent.getAction().equals("MobileInsight.UlLatBreakdownAnalyzer.UL_LAT_BREAKDOWN")){

            Log.i("Yuanjie-Game", "MobileInsight.UlLatBreakdownAnalyzer.UL_LAT_BREAKDOWN");

        }
        else if(intent.getAction().equals("MobileInsight.RrcSrAnalyzer.RRC_SR")){

            Log.i("Yuanjie-Game","MobileInsight.RrcSrAnalyzer.RRC_SR");

        }
    }
};
