package com.pi.androidmonitor;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataListAdapter extends RecyclerView.Adapter<DataListAdapter.ViewHolder> {
    private List<String> data = new ArrayList<>();
    private int textColor;

    public DataListAdapter(int textColor) {
        this.textColor = textColor;
    }

    public void setData(String[] newData) {
        this.data = new ArrayList<>(Arrays.asList(newData));
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String text = data.get(position);
        // Basic ANSI code stripping for now
        text = text.replaceAll("\u001B\\[[;\\d]*m", "");
        holder.textView.setText(text);
        
        // Command Center Styling
        holder.textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        holder.textView.setTextSize(11f);
        
        if (text.contains("[ERROR]")) {
            holder.textView.setTextColor(Color.parseColor("#CF6679")); // Error Red
        } else if (text.contains("[WARN]")) {
            holder.textView.setTextColor(Color.parseColor("#FBC02D")); // Warn Yellow
        } else if (text.contains("[INFO]")) {
            holder.textView.setTextColor(Color.parseColor("#03DAC5")); // Info Cyan
        } else {
            holder.textView.setTextColor(textColor);
        }
        
        holder.textView.setPadding(12, 4, 12, 4);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
        }
    }
}