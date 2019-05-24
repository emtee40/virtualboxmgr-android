package com.kedzie.vbox.machine.group;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipData.Item;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ViewFlipper;

import com.google.common.base.Predicate;
import com.kedzie.vbox.R;
import com.kedzie.vbox.api.IHost;
import com.kedzie.vbox.api.IMachine;
import com.kedzie.vbox.api.ISession;
import com.kedzie.vbox.api.jaxb.LockType;
import com.kedzie.vbox.api.jaxb.SessionState;
import com.kedzie.vbox.app.Utils;
import com.kedzie.vbox.host.HostView;
import com.kedzie.vbox.machine.MachineView;
import com.kedzie.vbox.machine.group.VMGroupPanel.OnDrillDownListener;
import com.kedzie.vbox.soap.VBoxSvc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scrollable list of {@link VMGroup} objects with drill-down support to focus on a particular group.
 */
@SuppressLint("NewApi")
public class VMGroupListView extends ViewFlipper implements OnClickListener, OnLongClickListener, OnDrillDownListener {
    private static final String TAG = "VMGroupListView";

    /**
     * Callback for element selection
     */
    public static interface OnTreeNodeSelectListener {
        /**
         * An element has been selected
         * @param node	the selected element
         */
        public void onTreeNodeSelect(TreeNode node);
    }

    static final int DRAG_ACCEPT_COLOR = Color.GREEN;
    static final int VIEW_BACKGROUND = android.R.color.background_light;

    /** Currently selected view */
    private View _selected;

    /** Is element selection enabled */
    private boolean mSelectionEnabled;

    private OnTreeNodeSelectListener _listener;

    /** Maps Machine ID to all views which reference it.  Used for updating views when events are received. */
    private Map<String, List<MachineView>> mMachineViewMap = new HashMap<String, List<MachineView>>();
    /** Maps {@link VMGroup} to all views which reference it.  Used for updating views when groups change. */
    private Map<String, List<VMGroupPanel>> mGroupViewMap = new HashMap<String, List<VMGroupPanel>>();
    
    private HostView mHostView;
    
    /** Cache of {@link VMGroup}s */
    private Map<String, VMGroup> mGroupCache = new HashMap<String, VMGroup>();
    
    private Animation mSlideInLeft = AnimationUtils.loadAnimation(getContext(), R.anim.slide_in_left);
    private Animation mSlideInRight = AnimationUtils.loadAnimation(getContext(), R.anim.slide_in_right);
    private Animation mSlideOutLeft = AnimationUtils.loadAnimation(getContext(), R.anim.slide_out_left);
    private Animation mSlideOutRight = AnimationUtils.loadAnimation(getContext(), R.anim.slide_out_right);

    private Dragger mDragger;
    private VMGroup mDraggedGroup;
    private IMachine mDraggedMachine;
    
    private IHost mHost;
    private String mVersion;

    private VBoxSvc _vmgr;

    public VMGroupListView(Context context, VBoxSvc vmgr) {
        super(context);
        _vmgr=vmgr;
        mDragger = new Dragger();
    }

    public void setRoot(VMGroup group, IHost host, String version) {
        mHost = host;
        mVersion = version;
        mGroupCache.put(group.getName(), group);
        addView(new GroupSection(getContext(), group));
    }

    @Override
    public void onDrillDown(VMGroup group) {
        addView(new GroupSection(getContext(), group));
        setInAnimation(mSlideInRight);
        setOutAnimation(mSlideOutLeft);
        showNext();
    }

    public void drillOut() {
        setInAnimation(mSlideInLeft);
        setOutAnimation(mSlideOutRight);
        showPrevious();
        removeViewAt(getChildCount()-1);
    }

    public void setOnTreeNodeSelectListener(OnTreeNodeSelectListener listener) {
        _listener = listener;
    }

    public boolean isSelectionEnabled() {
        return mSelectionEnabled;
    }

    public void setSelectionEnabled(boolean selectionEnabled) {
        mSelectionEnabled = selectionEnabled;
    }
    
    public TreeNode getSelectedObject() {
        if(_selected instanceof VMGroupPanel) {
            return ((VMGroupPanel)_selected).getGroup();
        } else if(_selected instanceof MachineView) {
            return ((MachineView)_selected).getMachine();
        } else if(_selected instanceof HostView) {
            return ((HostView)_selected).getHost();
        }
        return null;
    }
    
    public void setSelectedObject(TreeNode object) {
        //TODO implement programmatic selection
        if(object instanceof IHost) {
            
        } else if(object instanceof IMachine) {
            
        } else if(object instanceof VMGroup) {
            
        }
    }

    /**
     * Build a scrollable list of everything below a group
     */
    private class GroupSection extends LinearLayout {

        private VMGroup mGroup;
        private LinearLayout mContents;

        public GroupSection(Context context, VMGroup group) {
            super(context);
            mGroup = group;
            setOrientation(LinearLayout.VERTICAL);
            setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
            mContents = new LinearLayout(getContext());
            mContents.setOrientation(LinearLayout.VERTICAL);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if(!mGroup.getName().equals("/")) {
                LinearLayout header = (LinearLayout)LayoutInflater.from(getContext()).inflate(R.layout.vmgroup_list_header, null);
                ((ImageView)header.findViewById(R.id.group_back)).setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        drillOut();
                    }
                });
                Utils.setTextView(header, R.id.group_title, group.getName());
                Utils.setTextView(header, R.id.group_num_groups, group.getNumGroups());
                Utils.setTextView(header, R.id.group_num_machine, group.getNumMachines());
                lp.bottomMargin = Utils.dpiToPx(getContext(), 4);
                super.addView(header, lp);
            } else {
                //add mHostViewost view
                mHostView = new HostView(getContext());
                mHostView.update(mHost, mVersion);
                mHostView.setBackgroundResource(R.drawable.list_selector_color);
                mHostView.setClickable(true);
                mHostView.setOnClickListener(VMGroupListView.this);
                mContents.addView(mHostView);
            }
            for(TreeNode child : group.getChildren()) 
                mContents.addView(createView(child), lp);
            ScrollView scrollView = new ScrollView(getContext());
            scrollView.addView(mContents);
            super.addView(scrollView);
            setOnDragListener(mDragger);
        }
        
        @Override
        public void addView(View child, android.view.ViewGroup.LayoutParams params) {
            mContents.addView(child, params);
        }

        /**
         * Create a view for a single node in the tree
         * @param node      tree node
         * @return  Fully populated view representing the node
         */
        public View createView(TreeNode node) {
            if(node instanceof IMachine) {
                MachineView view = new MachineView(getContext());
                IMachine m = (IMachine)node;
                view.update(m);
                view.setBackgroundResource(R.drawable.list_selector_color);
                view.setClickable(true);
                view.setOnClickListener(VMGroupListView.this);
                view.setOnLongClickListener(VMGroupListView.this);
                if(!mMachineViewMap.containsKey(m.getIdRef()))
                    mMachineViewMap.put(m.getIdRef(), new ArrayList<MachineView>());
                mMachineViewMap.get(m.getIdRef()).add(view);
                return view;
            } else if (node instanceof VMGroup) {
                VMGroup group = (VMGroup)node;
                mGroupCache.put(group.getName(), group);
                VMGroupPanel groupView = new VMGroupPanel(getContext(), group);
                setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
                groupView.setOnClickListener(VMGroupListView.this);
                groupView.setOnDrillDownListener(VMGroupListView.this);
                groupView.setOnLongClickListener(VMGroupListView.this);
                for(TreeNode child : group.getChildren())
                    groupView.addChild(createView(child));
                if(!mGroupViewMap.containsKey(group.getName()))
                    mGroupViewMap.put(group.getName(), new ArrayList<VMGroupPanel>());
                mGroupViewMap.get(group.getName()).add(groupView);
                groupView.setBackgroundColor(getResources().getColor(VIEW_BACKGROUND, null));
                return groupView;
            }
            throw new IllegalArgumentException("Only views of type MachineView or VMGroupView are allowed");
        }

        public VMGroup getGroup() {
            return mGroup;
        }
        
        public List<VMGroupPanel> getNodeViews() {
            List<VMGroupPanel> children = new ArrayList<VMGroupPanel>(mContents.getChildCount());
            for(int i=0; i<mContents.getChildCount(); i++)
                if(mContents.getChildAt(i) instanceof VMGroupPanel)
                children.add((VMGroupPanel)mContents.getChildAt(i));
            return children;
        }
    }

    /**
     * Update all machine views with new data
     * @param machine       the machine to update (properties must be cached)
     */
    public void update(IMachine machine) {
        for(MachineView view : mMachineViewMap.get(machine.getIdRef()))
            view.update(machine);
    }

    @Override
    public void onClick(View v) {
        if(_listener==null)
            return;
        if(!mSelectionEnabled) {
            notifyListener(v);
            return;
        }
      //Deselect existing selection
        if(_selected==v) {
            _selected.setSelected(false);
            _selected=null;
            _listener.onTreeNodeSelect(null);
            return;
        } 
      //Make new Selection
        if(_selected!=null)
            _selected.setSelected(false);
        _selected=v;
        _selected.setSelected(true);
        notifyListener(_selected);
    }

    private void notifyListener(View v) {
        if(v instanceof MachineView)
            _listener.onTreeNodeSelect(((MachineView)v).getMachine());
        else if(v instanceof VMGroupPanel)
            _listener.onTreeNodeSelect(((VMGroupPanel)v).getGroup());
        else if(v instanceof HostView)
            _listener.onTreeNodeSelect(((HostView)v).getHost());
    }

    @Override
    public boolean onLongClick(View view) {
        if(view instanceof MachineView)
            new DragMachineTask().execute((MachineView)view);
        else if(view instanceof VMGroupPanel)
            new DragGroupTask().execute((VMGroupPanel)view);
        return true;
    }

    private class DragMachineTask extends AsyncTask<MachineView, Void, IMachine> {

        private MachineView mView;

        @Override
        protected IMachine doInBackground(MachineView... params) {
            mView = params[0];
            IMachine machine = mView.getMachine();
            if(machine.getSessionState().equals(SessionState.UNLOCKED)) {
                return machine;
            }
            return null;
        }

        @Override
        protected void onPostExecute(IMachine result) {
            super.onPostExecute(result);
            if(result!=null) {
                mDraggedMachine=result;
                ClipData data = new ClipData("VM", new String[] {"vbox/machine"}, new Item(result.getIdRef()));
                mView.startDrag(data, new DragShadowBuilder(mView), null, 0);
            }
        }
    }

    private class DragGroupTask extends AsyncTask<VMGroupPanel, Void, VMGroup> {

        private VMGroupPanel mView;

        @Override
        protected VMGroup doInBackground(VMGroupPanel... params) {
            mView = params[0];
            VMGroup group = mView.getGroup();
            if(!hasLockedMachines(group))
                return group;
            return null;
        }

        private boolean hasLockedMachines(VMGroup group) {
            boolean locked = false;
            for(TreeNode child : group.getChildren()) {
                if(child instanceof IMachine) {
                    IMachine machine = (IMachine)child;
                    locked |= !machine.getSessionState().equals(SessionState.UNLOCKED);
                } else {
                    VMGroup g = (VMGroup)child;
                    locked |= hasLockedMachines(g);
                }
            }
            return locked;
        }

        @Override
        protected void onPostExecute(VMGroup result) {
            super.onPostExecute(result);
            if(result!=null) {
                mDraggedGroup = result;
                ClipData data = new ClipData(result.getName(), new String[] {"vbox/group"}, new Item(result.getName()));
                mView.startDrag(data, new DragShadowBuilder(mView.getTitleView()), null, 0);
            }
        }
    }

    private class Dragger implements OnDragListener {

        private VMGroupPanel mGroupView;
        private GroupSection mSectionView;
        private VMGroup mParentGroup;
        private List<VMGroupPanel> mNewParentViews;

        @Override
        public boolean onDrag(View view, DragEvent event) {
            mSectionView = (GroupSection)view;

            final int action = event.getAction();
            switch(action) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    mSectionView.setBackgroundColor(DRAG_ACCEPT_COLOR);
                    mSectionView.invalidate();
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    VMGroupPanel current = (VMGroupPanel) Utils.TouchUtils.getDeepestView(mSectionView,
                            new PointF(event.getX(), event.getY()),
                            new Predicate<View>() {
                                @Override
                                public boolean apply(View view) {
                                    return VMGroupPanel.class.isAssignableFrom(view.getClass());
                                }
                            });

                    if(mGroupView!=null && current!=mGroupView) { //exited group panel
                        Log.d(TAG, "Exited " + mGroupView.getGroup());
                        mParentGroup=null;
                        mGroupView.setBackgroundColor(getResources().getColor(VIEW_BACKGROUND, null));
                        mGroupView.invalidate();
                    }
                    if(current!=null && current!=mGroupView) { //entered group panel
                        Log.d(TAG, "Entered " + current.getGroup());
                        mParentGroup = current.getGroup();
                        if(doAcceptDragEnter()) {
                            current.setBackgroundColor(DRAG_ACCEPT_COLOR);
                            current.invalidate();
                        }
                    }
                    if(current==null && mGroupView!=null) { //entered root group
                        Log.v(TAG, "Entered Root");
                        mParentGroup = mSectionView.getGroup();
                        if(doAcceptDragEnter()) {
                            mSectionView.setBackgroundColor(DRAG_ACCEPT_COLOR);
                            mSectionView.invalidate();
                        }
                    } else if(current!=null && mGroupView==null) { //exited root group
                        Log.v(TAG, "Exited Root");
                        mParentGroup=null;
                        mSectionView.setBackgroundColor(getResources().getColor(VIEW_BACKGROUND, null));
                        mSectionView.invalidate();
                    }
                    mGroupView = current;
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    mParentGroup = null;
                    view.setBackgroundColor(getResources().getColor(VIEW_BACKGROUND, null));
                    view.invalidate();
                    return true;
                case DragEvent.ACTION_DROP:
                    if(!doAcceptDragEnter())
                        return false;

                    mNewParentViews = mGroupViewMap.get(mParentGroup.getName());

                    if(mDraggedMachine!=null)
                        dropMachine(mDraggedMachine, mParentGroup, mDraggedMachine);
                    else if(mDraggedGroup!=null)
                        dropGroup(mDraggedGroup, mParentGroup, mDraggedGroup);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    if(mGroupView!=null) {
                        mGroupView.setBackgroundColor(getResources().getColor(VIEW_BACKGROUND, null));
                        mGroupView.invalidate();
                    }
                    view.setBackgroundColor(getResources().getColor(VIEW_BACKGROUND, null));
                    view.invalidate();
                    mGroupView = null;
                    mDraggedGroup=null;
                    mDraggedMachine=null;
                    mParentGroup=null;
                    return true;
            }
            return false;
        }

        private boolean doAcceptDragEnter() {
            if(mDraggedMachine!=null) {
                if(mDraggedMachine.getGroups().get(0).equals(mParentGroup.getName()))
                    return false;
            } else if(mDraggedGroup!=null) {
                if(mDraggedGroup.equals(mParentGroup))
                    return false;
                String oldParentName = mDraggedGroup.getName().substring(0, mDraggedGroup.getName().lastIndexOf('/'));
                if(oldParentName.equals(mParentGroup.getName()))
                    return false;
            }
            return true;
        }

        private void dropMachine(final IMachine draggedMachine, final VMGroup parent, IMachine child) {
            List<MachineView> machineViews = mMachineViewMap.get(draggedMachine.getIdRef());
            //move the views
            for(MachineView mv : machineViews)
                ((ViewGroup)mv.getParent()).removeView(mv);
            for(int i=0; i<machineViews.size(); i++) {
                MachineView mv = machineViews.get(i);
                if(mNewParentViews!=null && i<mNewParentViews.size()) //in case we are dragging to root group, there are less group panels than machine views
                    mNewParentViews.get(i).addView(mv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                else if(mSectionView!=null)
                    mSectionView.addView(mv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }
            //update the data
            VMGroup oldParent = mGroupCache.get(draggedMachine.getGroups().get(0));
            oldParent.removeChild(draggedMachine);
            parent.addChild(draggedMachine);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ISession session = _vmgr.getVBox().getSessionObject();
                        draggedMachine.lockMachine(session, LockType.WRITE);
                        IMachine mutable = session.getMachine();
                        mutable.setGroups(parent.getName());
                        mutable.saveSettings();
                        session.unlockMachine();
                    } catch (IOException e) {
                        Log.e(TAG, "Error", e);
                    }
                }
            }).start();
        }

        private void dropGroup(final VMGroup draggedGroup, final VMGroup parent, VMGroup child) {
            String oldParentName = draggedGroup.getName().substring(0, draggedGroup.getName().lastIndexOf('/'));
            //move the views
            List<VMGroupPanel> groupViews = mGroupViewMap.get(draggedGroup.getName());
            for(VMGroupPanel gv : groupViews)
                ((ViewGroup)gv.getParent()).removeView(gv);

            for(int i=0; i<groupViews.size(); i++) {
                VMGroupPanel gv = groupViews.get(i);
                if(mNewParentViews!=null && i<mNewParentViews.size()) //in case we are dragging to root group, there are less parent group panels than child views
                    mNewParentViews.get(i).addView(gv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                else if(mSectionView!=null)
                    mSectionView.addView(gv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }

            //update the data
            VMGroup oldParent = mGroupCache.get(oldParentName);
            Log.v(TAG, String.format("Moving group [%1$s]  from %2$s --> %2$s", draggedGroup, oldParentName, parent));
            oldParent.removeChild(draggedGroup);
            parent.addChild(draggedGroup);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        moveGroup(_vmgr.getVBox().getSessionObject(), parent, draggedGroup);
                    } catch(IOException e) {
                        Log.e(TAG, "Exception moving group", e);
                    }
                }
            }).start();
        }

        private void moveGroup(ISession session, VMGroup parent, VMGroup group) throws IOException {
            Log.v(TAG, "Processing group: " + group);
            String oldName = group.getName();
            group.setName(parent.getName() + (parent.getName().endsWith("/") ? "" : "/") + group.getSimpleGroupName()  );
            //update the group cache
            mGroupCache.remove(oldName);
            mGroupCache.put(group.getName(), group);
            Log.v(TAG, String.format("Changed group name %1$s --> %2$s", oldName, group.getName()));
            for(TreeNode c : group.getChildren()) {
                if(c instanceof IMachine) {
                    IMachine child = (IMachine)c;
                    Log.v(TAG, "Updating Machine: " + child.getName());
                    child.lockMachine(session, LockType.WRITE);
                    IMachine mutable = session.getMachine();
                    mutable.setGroups(group.getName());
                    mutable.saveSettings();
                    session.unlockMachine();
                } else {
                    VMGroup child = (VMGroup)c;
                    moveGroup(session, group, child);
                }
            }
        }
    }
}
