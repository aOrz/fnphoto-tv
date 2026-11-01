package com.fnphoto.tv;

import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

/** Paging is a status/action, never a media thumbnail. Keep its identity for DPAD handoff. */
final class PageFooterPresenter extends Presenter {
    @Override public ViewHolder onCreateViewHolder(ViewGroup parent) {
        TextView status = new TextView(parent.getContext());
        float density = parent.getResources().getDisplayMetrics().density;
        status.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status.setMaxWidth(Math.min((int) (640 * density),
                Math.max(1, parent.getResources().getDisplayMetrics().widthPixels - (int) (192 * density))));
        status.setMinHeight((int) (48 * density));
        status.setPadding((int) (20 * density), (int) (12 * density),
                (int) (20 * density), (int) (12 * density));
        status.setTextSize(16);
        status.setTextColor(Color.parseColor("#D5DAE4"));
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setBackgroundResource(R.drawable.bg_page_footer);
        // Do not drop focus when a pending request becomes terminal. Existing demand logic
        // transfers to newly appended media, or leaves a stable endpoint for Up/Back.
        status.setFocusable(true);
        status.setFocusableInTouchMode(true);
        return new ViewHolder(status);
    }

    @Override public void onBindViewHolder(ViewHolder holder, Object item) {
        MediaItem action = (MediaItem) item;
        TextView status = (TextView) holder.view;
        status.setText(action.getTitle());
        status.setTag(R.id.media_item_id, action.getId());
        status.setTag(R.id.media_item_type, action.getType());
    }

    @Override public void onUnbindViewHolder(ViewHolder holder) {
        ((TextView) holder.view).setText(null);
        holder.view.setTag(R.id.media_item_id, null);
        holder.view.setTag(R.id.media_item_type, null);
    }
}
