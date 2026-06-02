package com.termux.app.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.workspace.WorkspaceManager;

import java.util.List;

public class WorkspaceListActivity extends Activity {

    private ArrayAdapter<String> mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_list);

        ListView list = findViewById(R.id.workspace_list);
        mAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(mAdapter);
        refresh();

        list.setOnItemClickListener((parent, view, position, id) -> {
            String name = WorkspaceManager.listWorkspaces().get(position);
            String err = WorkspaceManager.setActiveWorkspace(this, name);
            toast(err == null ? "Active workspace: " + name + " (applies to new sessions)" : err);
            refresh();
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            String name = WorkspaceManager.listWorkspaces().get(position);
            confirmDelete(name);
            return true;
        });

        Button create = findViewById(R.id.workspace_create_button);
        create.setOnClickListener(v -> promptCreate());
    }

    private void refresh() {
        List<String> names = WorkspaceManager.listWorkspaces();
        String active = WorkspaceManager.getActiveWorkspaceName(this);
        mAdapter.clear();
        for (String n : names) mAdapter.add(n.equals(active) ? n + "  ✓" : n);
        mAdapter.notifyDataSetChanged();
    }

    private void promptCreate() {
        EditText input = new EditText(this);
        new AlertDialog.Builder(this)
            .setTitle("New workspace")
            .setMessage("Name: lowercase letters, digits, - or _")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String err = WorkspaceManager.createWorkspace(input.getText().toString().trim());
                toast(err == null ? "Created" : err);
                refresh();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDelete(String name) {
        new AlertDialog.Builder(this)
            .setTitle("Delete workspace")
            .setMessage("Delete '" + name + "' and all its files?")
            .setPositiveButton("Delete", (d, w) -> {
                String err = WorkspaceManager.deleteWorkspace(this, name);
                toast(err == null ? "Deleted" : err);
                refresh();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
