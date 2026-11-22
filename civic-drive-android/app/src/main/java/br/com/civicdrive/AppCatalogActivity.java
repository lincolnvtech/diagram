package br.com.civicdrive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Catálogo de todas as atividades launchable com seus próprios ícones. */
public final class AppCatalogActivity extends Activity {
    private final List<ResolveInfo> all = new ArrayList<>();
    private final List<ResolveInfo> visible = new ArrayList<>();
    private PackageManager pm;
    private AppsAdapter adapter;
    private int selectSlot = -1;
    private static final int WHITE=Color.WHITE, SECONDARY=Color.rgb(152,179,205), BLUE=Color.rgb(32,140,254);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        selectSlot=getIntent().getIntExtra("select_slot",-1);
        pm=getPackageManager();
        Intent probe=new Intent(Intent.ACTION_MAIN);probe.addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            List<ResolveInfo> found=pm.queryIntentActivities(probe,0);
            for(ResolveInfo info:found) {
                if(info.activityInfo!=null&&!getPackageName().equals(info.activityInfo.packageName)
                        && info.activityInfo.exported && info.loadLabel(pm)!=null) all.add(info);
            }
        } catch(Exception ignored) { }
        Collections.sort(all,Comparator.comparing(a->a.loadLabel(pm).toString().toLowerCase(new Locale("pt","BR"))));
        visible.addAll(all);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(17),dp(11),dp(17),dp(12));root.setBackgroundColor(Color.rgb(3,9,18));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header);
        TextView back=text("‹  VOLTAR",17,WHITE,true);header.addView(back);back.setPadding(0,0,dp(20),0);back.setOnClickListener(v->finish());
        TextView title=text(selectSlot>=0?"ESCOLHER ATALHO  "+(selectSlot+1):"APLICATIVOS DA CENTRAL",21,WHITE,true);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView countLabel=text(""+all.size()+" apps",13,SECONDARY,false);header.addView(countLabel);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextColor(WHITE);search.setHintTextColor(SECONDARY);
        search.setTextSize(15);search.setHint("Buscar rádio, Waze, música, qualquer aplicativo...");
        search.setPadding(dp(16),dp(6),dp(16),dp(6));search.setBackground(outline());
        LinearLayout.LayoutParams searchP=new LinearLayout.LayoutParams(-1,dp(48));searchP.setMargins(0,dp(13),0,dp(10));root.addView(search,searchP);
        GridView grid=new GridView(this);grid.setNumColumns(getResources().getDisplayMetrics().widthPixels>850?6:4);
        grid.setVerticalSpacing(dp(9));grid.setHorizontalSpacing(dp(9));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter=new AppsAdapter();grid.setAdapter(adapter);root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        TextView note=text(selectSlot>=0?"Toque no app para fixar o atalho na home. Segure na home para reorganizar pelo menu.":
                "Toque para abrir. Segure um aplicativo para adicionar à tela inicial.",12,SECONDARY,false);
        note.setPadding(0,dp(9),0,0);root.addView(note);
        grid.setOnItemClickListener((parent,view,index,id)->{
            ResolveInfo item=visible.get(index);
            if(selectSlot>=0)pin(item,selectSlot);else launch(item);
        });
        grid.setOnItemLongClickListener((parent,view,index,id)->{
            ResolveInfo item=visible.get(index);
            new AlertDialog.Builder(this).setTitle("Adicionar atalho: "+item.loadLabel(pm))
                    .setItems(new String[]{"Posição 1","Posição 2","Posição 3"},(d,slot)->pin(item,slot))
                    .setNegativeButton("Cancelar",null).show();return true;
        });
        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                String query=s.toString().toLowerCase(new Locale("pt","BR")).trim();visible.clear();
                for(ResolveInfo i:all)if(query.isEmpty()||i.loadLabel(pm).toString().toLowerCase(new Locale("pt","BR")).contains(query)
                        ||i.activityInfo.packageName.toLowerCase(Locale.ROOT).contains(query))visible.add(i);
                adapter.notifyDataSetChanged();countLabel.setText(visible.size()+" apps");
            }
            @Override public void afterTextChanged(Editable s){}
        });
        setContentView(root);
    }
    private void pin(ResolveInfo app,int slot){
        if(slot<0||slot>2)return;
        boolean ok=getSharedPreferences("civic_shortcuts",MODE_PRIVATE).edit()
                .putString("pkg"+slot,app.activityInfo.packageName)
                .putString("activity"+slot,app.activityInfo.name)
                .putString("label"+slot,app.loadLabel(pm).toString()).commit();
        Toast.makeText(this,ok?"Atalho adicionado à tela inicial":"Não foi possível salvar",Toast.LENGTH_SHORT).show();
        if(ok)finish();
    }
    private void launch(ResolveInfo app) {
        try {Intent i=new Intent(Intent.ACTION_MAIN);i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(app.activityInfo.packageName,app.activityInfo.name));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);
        } catch(Exception ex) {Toast.makeText(this,"Este aplicativo não permite abertura externa.",Toast.LENGTH_LONG).show();}
    }
    private final class AppsAdapter extends BaseAdapter {
        @Override public int getCount(){return visible.size();}
        @Override public Object getItem(int i){return visible.get(i);}
        @Override public long getItemId(int i){return i;}
        @Override public View getView(int position,View convertView,android.view.ViewGroup parent){
            ResolveInfo app=visible.get(position);
            LinearLayout tile=new LinearLayout(AppCatalogActivity.this);tile.setGravity(Gravity.CENTER);tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(5),dp(9),dp(5),dp(6));tile.setBackground(outline());
            ImageView image=new ImageView(AppCatalogActivity.this);
            try {image.setImageDrawable(app.loadIcon(pm));}catch(Exception ignored){}
            tile.addView(image,new LinearLayout.LayoutParams(dp(39),dp(39)));
            TextView name=text(app.loadLabel(pm).toString(),12,WHITE,false);
            name.setMaxLines(2);name.setGravity(Gravity.CENTER);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(36));np.topMargin=dp(7);tile.addView(name,np);
            tile.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,dp(104)));
            return tile;
        }
    }
    private GradientDrawable outline(){GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(12,23,39));d.setCornerRadius(dp(13));d.setStroke(dp(1),Color.rgb(30,86,156));return d;}
    private TextView text(String v,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
}
