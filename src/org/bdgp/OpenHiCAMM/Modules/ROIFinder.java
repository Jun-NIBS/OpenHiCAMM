package org.bdgp.OpenHiCAMM.Modules;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.process.ImageProcessor;

import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import mmcorej.TaggedImage;

import org.bdgp.OpenHiCAMM.Dao;
import org.bdgp.OpenHiCAMM.ImageLog;
import org.bdgp.OpenHiCAMM.ImageLog.ImageLogRecord;
import org.bdgp.OpenHiCAMM.ImageLog.ImageLogRunner;
import org.bdgp.OpenHiCAMM.Logger;
import org.bdgp.OpenHiCAMM.OpenHiCAMM;
import org.bdgp.OpenHiCAMM.Util;
import org.bdgp.OpenHiCAMM.ValidationError;
import org.bdgp.OpenHiCAMM.WorkflowRunner;
import org.bdgp.OpenHiCAMM.DB.Acquisition;
import org.bdgp.OpenHiCAMM.DB.Config;
import org.bdgp.OpenHiCAMM.DB.Image;
import org.bdgp.OpenHiCAMM.DB.ModuleConfig;
import org.bdgp.OpenHiCAMM.DB.ROI;
import org.bdgp.OpenHiCAMM.DB.Slide;
import org.bdgp.OpenHiCAMM.DB.SlidePos;
import org.bdgp.OpenHiCAMM.DB.SlidePosList;
import org.bdgp.OpenHiCAMM.DB.Task;
import org.bdgp.OpenHiCAMM.DB.Task.Status;
import org.bdgp.OpenHiCAMM.DB.TaskDispatch;
import org.bdgp.OpenHiCAMM.DB.WorkflowModule;
import org.bdgp.OpenHiCAMM.Modules.Interfaces.Configuration;
import org.bdgp.OpenHiCAMM.Modules.Interfaces.ImageLogger;
import org.bdgp.OpenHiCAMM.Modules.Interfaces.Module;
import org.json.JSONException;
import org.micromanager.acquisition.MMAcquisition;
import org.micromanager.api.ImageCache;
import org.micromanager.api.MultiStagePosition;
import org.micromanager.api.PositionList;
import org.micromanager.api.ScriptInterface;
import org.micromanager.api.StagePosition;
import org.micromanager.utils.ImageUtils;
import org.micromanager.utils.MDUtils;
import org.micromanager.utils.MMSerializationException;

import static org.bdgp.OpenHiCAMM.Util.where;

/**
 * Return x/y/len/width of bounding box surrounding the ROI
 */
public class ROIFinder implements Module, ImageLogger {
    WorkflowRunner workflowRunner;
    String moduleId;
    ScriptInterface script;

    @Override
    public void initialize(WorkflowRunner workflowRunner, String moduleId) {
        this.workflowRunner = workflowRunner;
        this.moduleId = moduleId;
        OpenHiCAMM mmslide = workflowRunner.getOpenHiCAMM();
        this.script = mmslide.getApp();
        
        // set initial configs
        workflowRunner.getModuleConfig().insertOrUpdate(
                new ModuleConfig(this.moduleId, "canProduceROIs", "yes"), 
                "id", "key");
    }

    @Override
    public Status run(Task task, Map<String,Config> config, final Logger logger) {
    	logger.fine(String.format("Running task: %s", task));
    	for (Config c : config.values()) {
            logger.fine(String.format("Using config: %s", c));
    	}

        // Get the Image record
    	Config imageIdConf = config.get("imageId");
    	if (imageIdConf == null) throw new RuntimeException("No imageId task configuration was set by the slide imager!");
        Integer imageId = new Integer(imageIdConf.getValue());
        Dao<Image> imageDao = workflowRunner.getInstanceDb().table(Image.class);
        final Image image = imageDao.selectOneOrDie(where("id",imageId));

        // get the tagged image
        TaggedImage taggedImage = getTaggedImage(image, logger);

        // get the image label and position name
        String positionName = null;
        try { positionName = MDUtils.getPositionName(taggedImage.tags); } 
        catch (JSONException e) {throw new RuntimeException(e);} 
        String imageLabel = image.getLabel();
        String label = String.format("%s (%s)", positionName, imageLabel); 
        
        logger.fine(String.format("%s: Using image: %s", label, image));
        logger.fine(String.format("%s: Using image ID: %d", label, imageId));
        
        Dao<Slide> slideDao = workflowRunner.getInstanceDb().table(Slide.class);
        Slide slide = slideDao.selectOneOrDie(where("id",image.getSlideId()));
        logger.fine(String.format("%s: Using slide: %s", label, slide));
        
    	try {

			double pixelSizeUm = new Double(config.get("pixelSizeUm").getValue());
			logger.fine(String.format("%s: Using pixelSizeUm: %f", label, pixelSizeUm));
			
			double hiResPixelSizeUm = new Double(config.get("hiResPixelSizeUm").getValue());
			logger.fine(String.format("%s: Using hiResPixelSizeUm: %f", label, hiResPixelSizeUm));

			double minRoiArea = new Double(config.get("minRoiArea").getValue());
			logger.fine(String.format("%s: Using minRoiArea: %f", label, minRoiArea));

			int positionIndex = MDUtils.getPositionIndex(taggedImage.tags);
			logger.fine(String.format("%s: Using positionIndex: %d", label, positionIndex));

			int imageWidth = MDUtils.getWidth(taggedImage.tags);
			int imageHeight = MDUtils.getHeight(taggedImage.tags);
			
            long cameraWidth = this.script.getMMCore().getImageWidth();
            long cameraHeight = this.script.getMMCore().getImageHeight();
           
			double x_stage = MDUtils.getXPositionUm(taggedImage.tags);
			double y_stage = MDUtils.getYPositionUm(taggedImage.tags);
			
			// Delete any old ROI records
            Dao<ROI> roiDao = this.workflowRunner.getInstanceDb().table(ROI.class);
			int deleted = roiDao.delete(where("imageId", imageId));
			logger.fine(String.format("%s: Deleted %d old ROI records.", label, deleted));
			
			// Fill in list of ROIs
			logger.fine(String.format("%s: Running process() to get list of ROIs", label));
			List<ROI> rois = new ArrayList<ROI>();
			try {
                rois = process(image, taggedImage, logger, new ImageLog.NullImageLogRunner(), minRoiArea);
			}
			catch (Exception e) {
			    logger.warning(String.format("Exception during ROI processing: %s", e.toString()));
			}

            // Convert the ROIs into a PositionList
			Map<MultiStagePosition,ROI> roiMap = new HashMap<MultiStagePosition,ROI>();
			PositionList posList = new PositionList();
			for (ROI roi : rois) {
			    // We need to potentially create a grid of Stage positions in order to capture all of the 
			    // ROI.
			    int roiWidth = roi.getX2()-roi.getX1()+1;
			    int roiHeight = roi.getY2()-roi.getY1()+1;
			    int tileWidth = (int)Math.floor(((double)cameraWidth * hiResPixelSizeUm) / pixelSizeUm);
			    int tileHeight = (int)Math.floor(((double)cameraHeight * hiResPixelSizeUm) / pixelSizeUm);
			    int tileXCount = (int)Math.ceil((double)roiWidth / (double)tileWidth);
			    int tileYCount = (int)Math.ceil((double)roiHeight / (double)tileHeight);
			    int tileSetWidth = tileXCount * tileWidth;
			    int tileSetHeight = tileYCount * tileHeight;
			    int tileXOffset = (int)Math.floor((roi.getX1() + ((double)roiWidth / 2.0)) - ((double)tileSetWidth / 2.0) + ((double)tileWidth / 2.0));
			    int tileYOffset = (int)Math.floor((roi.getY1() + ((double)roiHeight / 2.0)) - ((double)tileSetHeight / 2.0) + ((double)tileHeight / 2.0));

			    for (int x=0; x < tileXCount; ++x) {
                    for (int y=0; y < tileYCount; ++y) {
                        int tileX = (x*tileWidth) + tileXOffset;
                        int tileY = (y*tileHeight) + tileYOffset;
                        MultiStagePosition msp = new MultiStagePosition();
                        StagePosition sp = new StagePosition();
                        sp.numAxes = 2;
                        sp.stageName = "XYStage";
                        sp.x = x_stage-((tileX-(double)imageWidth/2.0)*pixelSizeUm);
                        sp.y = y_stage-((tileY-(double)imageHeight/2.0)*pixelSizeUm);
                        msp.setGridCoordinates(y, x);
                        String mspLabel = String.format("%s: ROI=%d, tileX=%d, tileY=%d", label, roi.getId(), x, y);
                        msp.setLabel(mspLabel);
                        msp.add(sp);
                        msp.setDefaultXYStage("XYStage");
                        posList.addPosition(msp);
                        roiMap.put(msp, roi);
                        logger.info(String.format("%s: Storing StagePosition(label=%s, numAxes: %d, stageName: %s, x=%.2f, y=%.2f, tileX=%d, tileY=%d)",
                                label, Util.escape(mspLabel), sp.numAxes, Util.escape(sp.stageName), sp.x, sp.y, tileX, tileY));
                    }
			    }
			}
			if (posList.getNumberOfPositions() > 0) {
			    logger.info(String.format("%s: Produced position list of %d ROIs", label, posList.getNumberOfPositions())); 
                try { logger.fine(String.format("%s: Position List:%n%s", label, posList.serialize())); } 
                catch (MMSerializationException e) {throw new RuntimeException(e);}
			}
			else {
			    logger.info(String.format("%s: ROIFinder did not produce any positions for this image", label));
			}

			Dao<SlidePosList> slidePosListDao = workflowRunner.getInstanceDb().table(SlidePosList.class);
            Dao<SlidePos> slidePosDao = workflowRunner.getInstanceDb().table(SlidePos.class);

			// First, delete any old slidepos and slideposlist records
			logger.fine(String.format("Deleting old position lists"));
			List<SlidePosList> oldSlidePosLists = slidePosListDao.select(
					where("slideId",slide.getId()).and("moduleId",this.moduleId).and("taskId", task.getId()));
			for (SlidePosList oldSlidePosList : oldSlidePosLists) {
				slidePosDao.delete(where("slidePosListId",oldSlidePosList.getId()));
			}
			slidePosListDao.delete(where("slideId",slide.getId()).and("moduleId",this.moduleId).and("taskId", task.getId()));

			// Create/Update SlidePosList and SlidePos DB records
			SlidePosList slidePosList = new SlidePosList(this.moduleId, slide.getId(), task.getId(), posList);
			slidePosListDao.insert(slidePosList);
			logger.fine(String.format("%s: Created new SlidePosList: %s", label, slidePosList));

			MultiStagePosition[] msps = posList.getPositions();
			for (int i=0; i<msps.length; ++i) {
			    ROI roi = roiMap.get(msps[i]);
			    if (roi == null) throw new RuntimeException(
			            String.format("Error: could not get ROI for MSP %s using roiMap", msps[i]));

				SlidePos slidePos = new SlidePos(slidePosList.getId(), i, roi.getId());
				slidePosDao.insert(slidePos);
                logger.fine(String.format("%s: Created new SlidePos record for position %d: %s", label, i, slidePos));
			}
			return Status.SUCCESS;
		} 
    	catch (JSONException e) { throw new RuntimeException(e); }
    }
    
    public TaggedImage getTaggedImage(Image image, Logger logger) {
        String label = image.getLabel();

        // Initialize the acquisition
        Dao<Acquisition> acqDao = workflowRunner.getInstanceDb().table(Acquisition.class);
        Acquisition acquisition = acqDao.selectOneOrDie(where("id",image.getAcquisitionId()));
        logger.fine(String.format("%s: Using acquisition: %s", label, acquisition));
        MMAcquisition mmacquisition = acquisition.getAcquisition(acqDao);

        // Get the image cache object
        ImageCache imageCache = mmacquisition.getImageCache();
        if (imageCache == null) throw new RuntimeException("Acquisition was not initialized; imageCache is null!");
        logger.fine(String.format("%s: ImageCache has following images: %s", label, imageCache.imageKeys()));
        logger.fine(String.format("%s: Attempting to grab image %s from imageCache", label, image));
        // Get the tagged image from the image cache
        TaggedImage taggedImage = image.getImage(imageCache);
        if (taggedImage == null) throw new RuntimeException(String.format("Acqusition %s, Image %s is not in the image cache!",
                acquisition, image));
        logger.fine(String.format("%s: Got taggedImage from ImageCache: %s", label, taggedImage));

        return taggedImage;
    }
    
    public List<ROI> process(Image image, TaggedImage taggedImage, Logger logger, ImageLogRunner imageLog, double minRoiArea) {
        // get the image label
        String positionName = null;
        try { positionName = MDUtils.getPositionName(taggedImage.tags); } 
        catch (JSONException e) {throw new RuntimeException(e);} 
        String imageLabel = image.getLabel();
        String label = String.format("%s (%s)", positionName, imageLabel); 
    	
    	List<ROI> rois = new ArrayList<ROI>();
        ImageProcessor processor = ImageUtils.makeProcessor(taggedImage);
        ImagePlus imp = new ImagePlus(image.toString(), processor);
        //imp.show();
        
        // Convert to gray
        logger.fine(String.format("%s: Convert to gray", label));
        IJ.run(imp, "8-bit", "");
        imageLog.addImage(imp, "Convert to gray");
        
        // Subtract background
//        IJ.run(imp, "Subtract Background...", "rolling=200 light");
//        imageLog.addImage(imp, "Subtract Background");

        // Resize to 1/4
        int w=imp.getWidth();
        int h=imp.getHeight();
        logger.fine(String.format("%s: Image dimensions: (%d,%d)", label, w, h));

//        double scale = 0.25;
        double scale = 1.0;

        double ws=(double)w*scale;
        double hs=(double)h*scale;

        String scaleOp = String.format("x=%f y=%f width=%d height=%d interpolation=Bicubic average", 
        		scale, scale, (int)ws, (int)hs);
        logger.fine(String.format("%s: Scaling: %s", label, scaleOp));
        IJ.run(imp, "Scale...", scaleOp);
        imageLog.addImage(imp, "Scaling: scaleOp");

        // Crop after scale
        double crop = 2.0;
        double rw=(w/crop)-(ws/crop);
        double rh=(h/crop)-(hs/crop);
        logger.fine(String.format("%s: Cropping: %d, %d, %d, %d", label, (int)rw, (int)rh, (int)ws, (int)hs));
        imp.setRoi((int)rw,(int)rh,(int)ws,(int)hs);
        IJ.run(imp, "Crop", "");
        imageLog.addImage(imp, String.format("Cropping: %d, %d, %d, %d", (int)rw, (int)rh, (int)ws, (int)hs));

        // Binarize
        logger.fine(String.format("%s: Binarizing", label));
        IJ.run(imp, "Auto Threshold", "method=Huang white"); // array out of bounds exception
        imageLog.addImage(imp, "Binarizing");

        // Morphological operations: close gaps, fill holes
        logger.fine(String.format("%s: Closing gaps", label));
        IJ.run(imp, "Close-", "");
        imageLog.addImage(imp, "Closing gaps");

        logger.fine(String.format("%s: Filling holes", label));
        IJ.run(imp, "Fill Holes", "");
        imageLog.addImage(imp, "Filling holes");

        // Set the measurements
        logger.fine(String.format("%s: Set the measurements", label));
        IJ.run(imp, "Set Measurements...", "area mean min bounding redirect=None decimal=3");
        imageLog.addImage(imp, "Set measurements");

        // Detect the objects
        logger.fine(String.format("%s: Analyzing particles", label));
        IJ.run(imp, "Analyze Particles...", "exclude clear in_situ");
        imageLog.addImage(imp, "Analyzing particles");
       
        Dao<ROI> roiDao = this.workflowRunner.getInstanceDb().table(ROI.class);
        // Get the objects and iterate through them
        ResultsTable rt = Analyzer.getResultsTable();
        logger.fine(String.format("ResultsTable Column Headings: %s", rt.getColumnHeadings()));
        for (int i=0; i < rt.getCounter(); i++) {
            double area = rt.getValue("Area", i) / (scale*scale); // area of the object
            double bx = rt.getValue("BX", i) / scale; // x of bounding box
            double by = rt.getValue("BY", i) / scale; // y of bounding box
            double width = rt.getValue("Width", i) / scale; // width of bounding box
            double height = rt.getValue("Height", i) / scale; // height of bounding box
            logger.finest(String.format(
            		"Found object: area=%.2f, bx=%.2f, by=%.2f, width=%.2f, height=%.2f",
            		area, bx, by, width, height));

            // Area usually > 18,000 but embryo may be cut off at boundary; don’t know how your ROI code would deal with that
            // -> I’d suggest:
            // Select for area > 2000, check if object is at boundary of image (bx or by == 1)
            // ROI: upper left corner = bx/by with width/height
            if (area >= minRoiArea && bx > 1 && by > 1 && bx+width < w && by+height < h) {
                ROI roi = new ROI(image.getId(), (int)(bx*scale), (int)(by*scale), (int)(bx+width), (int)(by+height));
                rois.add(roi);
                roiDao.insert(roi);
                logger.info(String.format("%s: Created new ROI record with width=%.2f, height=%.2f, area=%.2f: %s", 
                        label, width, height, area, roi));
                
                // Draw the ROI rectangle
                imp.setRoi(roi.getX1(), roi.getY1(), roi.getX2()-roi.getX1()+1, roi.getY2()-roi.getY1()+1);
                IJ.setForegroundColor(255, 255, 0);
                IJ.run(imp, "Draw", "");
            }
            else {
            	if (area < minRoiArea) {
            		logger.finest(String.format("Skipping, area %.2f is less than %.2f", area, minRoiArea));
            	}
            	if (!(bx > 1 && by > 1 && bx+width < w && by+height < h)) {
            		logger.finest(String.format("Skipping, ROI hits edge"));
            	}
            }
        }
        imageLog.addImage(imp, "Adding ROIs to image");
    	return rois;
    }
    
    @Override
    public String getTitle() {
        return this.getClass().getName();
    }

    @Override
    public String getDescription() {
        return this.getClass().getName();
    }

    @Override
    public Configuration configure() {
        return new Configuration() {
            ROIFinderDialog dialog = new ROIFinderDialog(ROIFinder.this);
            @Override
            public Config[] retrieve() {
            	List<Config> configs = new ArrayList<Config>();

            	Double pixelSizeUm = (Double)dialog.pixelSizeUm.getValue();
            	if (pixelSizeUm != null) {
            	    configs.add(new Config(ROIFinder.this.moduleId, "pixelSizeUm", pixelSizeUm.toString()));
            	}

            	Double hiResPixelSizeUm = (Double)dialog.hiResPixelSizeUm.getValue();
            	if (hiResPixelSizeUm != null) {
            	    configs.add(new Config(ROIFinder.this.moduleId, "hiResPixelSizeUm", hiResPixelSizeUm.toString()));
            	}

            	Double minRoiArea = (Double)dialog.minRoiArea.getValue();
            	if (minRoiArea != null) {
            	    configs.add(new Config(ROIFinder.this.moduleId, "minRoiArea", minRoiArea.toString()));
            	}
                return configs.toArray(new Config[0]);
            }
            @Override
            public Component display(Config[] configs) {
            	Map<String,Config> confs = new HashMap<String,Config>();
            	for (Config c : configs) {
            		confs.put(c.getKey(), c);
            	}

            	if (confs.containsKey("pixelSizeUm")) {
            	    dialog.pixelSizeUm.setValue(new Double(confs.get("pixelSizeUm").getValue()));
            	}
            	if (confs.containsKey("hiResPixelSizeUm")) {
            	    dialog.hiResPixelSizeUm.setValue(new Double(confs.get("hiResPixelSizeUm").getValue()));
            	}
            	if (confs.containsKey("minRoiArea")) {
            	    dialog.minRoiArea.setValue(new Double(confs.get("minRoiArea").getValue()));
            	}
                return dialog;
            }
            @Override
            public ValidationError[] validate() {
            	List<ValidationError> errors = new ArrayList<ValidationError>();

            	Double pixelSizeUm = (Double)dialog.pixelSizeUm.getValue();
            	if (pixelSizeUm == null || pixelSizeUm == 0.0) {
            	    errors.add(new ValidationError(ROIFinder.this.moduleId, "Please enter a nonzero value for pixelSizeUm"));
            	}

            	Double hiResPixelSizeUm = (Double)dialog.hiResPixelSizeUm.getValue();
            	if (hiResPixelSizeUm == null || hiResPixelSizeUm == 0.0) {
            	    errors.add(new ValidationError(ROIFinder.this.moduleId, "Please enter a nonzero value for hiResPixelSizeUm"));
            	}

            	Double minRoiArea = (Double)dialog.minRoiArea.getValue();
            	if (minRoiArea == null || minRoiArea == 0.0) {
            	    errors.add(new ValidationError(ROIFinder.this.moduleId, "Please enter a nonzero value for Min ROI Area"));
            	}
                return errors.toArray(new ValidationError[0]);
            }
        };
    }

    @Override
    public List<Task> createTaskRecords(List<Task> parentTasks) {
        WorkflowModule module = workflowRunner.getWorkflow().selectOneOrDie(where("id",moduleId));
        List<Task> tasks = new ArrayList<Task>();
        if (module.getParentId() != null) {
            for (Task parentTask : parentTasks) {
            	workflowRunner.getLogger().fine(String.format("%s: createTaskRecords: attaching to parent Task: %s",
            			this.moduleId, parentTask));
                Task task = new Task(moduleId, Status.NEW);
                workflowRunner.getTaskStatus().insert(task);
                tasks.add(task);
            	workflowRunner.getLogger().fine(String.format("%s: createTaskRecords: Created new task record: %s",
            			this.moduleId, task));
                
                TaskDispatch dispatch = new TaskDispatch(task.getId(), parentTask.getId());
                workflowRunner.getTaskDispatch().insert(dispatch);
            	workflowRunner.getLogger().fine(String.format("%s: createTaskRecords: Created new task dispatch record: %s",
            			this.moduleId, dispatch));
            }
        }
        return tasks;
    }

	@Override
	public TaskType getTaskType() {
		return Module.TaskType.PARALLEL;
	}

	@Override public void cleanup(Task task) { }

    @Override
    public List<ImageLogRecord> logImages(final Task task, final Map<String,Config> config, final Logger logger) {
        List<ImageLogRecord> imageLogRecords = new ArrayList<ImageLogRecord>();

        // Add an image logger instance to the workflow runner for this module
        imageLogRecords.add(new ImageLogRecord(task.getName(), task.getName(),
                new FutureTask<ImageLogRunner>(new Callable<ImageLogRunner>() {
            @Override public ImageLogRunner call() throws Exception {
                // Get the Image record
                Config imageIdConf = config.get("imageId");
                if (imageIdConf != null) {
                    Integer imageId = new Integer(imageIdConf.getValue());

                    logger.info(String.format("Using image ID: %d", imageId));
                    Dao<Image> imageDao = workflowRunner.getInstanceDb().table(Image.class);
                    final Image image = imageDao.selectOneOrDie(where("id",imageId));
                    logger.info(String.format("Using image: %s", image));

                    // Initialize the acquisition
                    Dao<Acquisition> acqDao = workflowRunner.getInstanceDb().table(Acquisition.class);
                    Acquisition acquisition = acqDao.selectOneOrDie(where("id",image.getAcquisitionId()));
                    logger.info(String.format("Using acquisition: %s", acquisition));
                    MMAcquisition mmacquisition = acquisition.getAcquisition(acqDao);

                    // Get the image cache object
                    ImageCache imageCache = mmacquisition.getImageCache();
                    if (imageCache == null) throw new RuntimeException("Acquisition was not initialized; imageCache is null!");
                    // Get the tagged image from the image cache
                    TaggedImage taggedImage = image.getImage(imageCache);
                    logger.info(String.format("Got taggedImage from ImageCache: %s", taggedImage));

                    double minRoiArea = new Double(config.get("minRoiArea").getValue());

                    ImageLogRunner imageLogRunner = new ImageLogRunner(task.getName());
                    ROIFinder.this.process(image, taggedImage, logger, imageLogRunner, minRoiArea);
                    return imageLogRunner;
                }
                return null;
            }
        })));
        return imageLogRecords;
    }

    @Override
    public void runIntialize() { }
}
